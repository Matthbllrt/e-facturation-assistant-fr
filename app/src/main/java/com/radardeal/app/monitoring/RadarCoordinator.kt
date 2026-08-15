package com.radardeal.app.monitoring

import com.radardeal.app.core.RdLog
import com.radardeal.app.data.prefs.SettingsStore
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.domain.model.Watch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Aggregate outcome of one pass over the due watches. */
data class TickResult(
    val scanned: Int = 0,
    val newListings: Int = 0,
    val priceDrops: Int = 0,
    val problems: List<ScanStatus> = emptyList(),
)

/**
 * Decides *when* each watch is scanned, and runs the scans one at a time.
 *
 * Two deliberate choices:
 *
 *  * **Sequential, spaced scans.** Watches are never scanned in parallel and a short pause
 *    separates them. Firing ten simultaneous requests at Vinted would be both rude and the
 *    fastest way to get rate limited.
 *  * **Automatic back-off.** A watch that hits 429, a network failure or a verification wall is
 *    slowed down progressively instead of being retried at full speed. A successful scan
 *    resets it. This is the app adapting to Vinted's limits, not working around them.
 */
class RadarCoordinator(
    private val watchRepository: WatchRepository,
    private val scanEngine: ScanEngine,
    private val settingsStore: SettingsStore,
) {

    private val tickMutex = Mutex()

    /** watchId → epoch millis before which the watch must not be scanned again. */
    private val nextDueAt = ConcurrentHashMap<Long, Long>()

    /** watchId → current back-off multiplier. */
    private val backoff = ConcurrentHashMap<Long, Int>()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastTickAt = MutableStateFlow<Long?>(null)
    val lastTickAt: StateFlow<Long?> = _lastTickAt.asStateFlow()

    /**
     * Scans every active watch whose interval has elapsed. Safe to call from the service loop
     * and from WorkManager at the same time — the mutex makes overlapping calls a no-op.
     */
    suspend fun tick(now: Long = System.currentTimeMillis()): TickResult = tickMutex.withLock {
        val settings = runCatching { settingsStore.settings.first() }.getOrNull()
            ?: return@withLock TickResult()

        val active = runCatching { watchRepository.getActive() }.getOrElse {
            RdLog.w("Radar", "could not read watches", it)
            emptyList()
        }

        val due = active.filter { watch -> (nextDueAt[watch.id] ?: 0L) <= now }
        if (due.isEmpty()) return@withLock TickResult()

        _isScanning.value = true
        var newListings = 0
        var priceDrops = 0
        val problems = mutableListOf<ScanStatus>()

        try {
            due.forEachIndexed { index, watch ->
                if (index > 0) delay(SPACING_MS)
                val report = scanEngine.scan(watch, settings)
                newListings += report.newCount
                priceDrops += report.priceDropCount
                if (report.status.isProblem) problems += report.status
                schedule(watch, report.status)
            }
        } finally {
            _isScanning.value = false
            _lastTickAt.value = System.currentTimeMillis()
        }

        return@withLock TickResult(due.size, newListings, priceDrops, problems)
    }

    /** "Scanner maintenant" — bypasses the schedule for a single watch. */
    suspend fun scanNow(watchId: Long): ScanReport? {
        val watch = runCatching { watchRepository.getWatch(watchId) }.getOrNull() ?: return null
        val settings = runCatching { settingsStore.settings.first() }.getOrNull() ?: return null

        _isScanning.value = true
        return try {
            val report = scanEngine.scan(watch, settings)
            schedule(watch, report.status)
            report
        } finally {
            _isScanning.value = false
            _lastTickAt.value = System.currentTimeMillis()
        }
    }

    /** Makes a watch eligible for the very next tick, e.g. right after it was created. */
    fun markDueNow(watchId: Long) {
        nextDueAt[watchId] = 0L
        backoff.remove(watchId)
    }

    fun forget(watchId: Long) {
        nextDueAt.remove(watchId)
        backoff.remove(watchId)
    }

    /** Shortest interval among active watches — drives how often the service loop wakes up. */
    suspend fun shortestInterval(): Int = runCatching {
        watchRepository.getActive().minOfOrNull { it.intervalSeconds } ?: DEFAULT_IDLE_SECONDS
    }.getOrDefault(DEFAULT_IDLE_SECONDS)

    // --- Scheduling ---------------------------------------------------------------------------

    private fun schedule(watch: Watch, status: ScanStatus) {
        val factor = nextBackoff(watch.id, status)
        val delaySeconds = when (status) {
            // No amount of retrying fixes these; only the user can. Check back occasionally
            // so monitoring resumes by itself once they have signed in or passed the check.
            ScanStatus.NEEDS_LOGIN, ScanStatus.NEEDS_VERIFICATION ->
                maxOf(watch.intervalSeconds, BLOCKED_RETRY_SECONDS)

            else -> watch.intervalSeconds * factor
        }.coerceAtMost(MAX_DELAY_SECONDS)

        nextDueAt[watch.id] = System.currentTimeMillis() + delaySeconds * 1000L

        if (factor > 1) {
            RdLog.d("Radar", "watch ${watch.id} backing off to ${delaySeconds}s ($status)")
        }
    }

    private fun nextBackoff(watchId: Long, status: ScanStatus): Int {
        val current = backoff[watchId] ?: 1
        val next = when (status) {
            ScanStatus.RATE_LIMITED -> (current * 2).coerceAtMost(MAX_BACKOFF)
            ScanStatus.NETWORK_ERROR, ScanStatus.PARSE_ERROR -> (current * 2).coerceAtMost(8)
            else -> 1
        }
        if (next == 1) backoff.remove(watchId) else backoff[watchId] = next
        return next
    }

    private companion object {
        /** Pause between two watches inside one tick. */
        const val SPACING_MS = 1_500L
        const val MAX_BACKOFF = 16
        const val MAX_DELAY_SECONDS = 30 * 60
        const val BLOCKED_RETRY_SECONDS = 5 * 60
        const val DEFAULT_IDLE_SECONDS = 60
    }
}
