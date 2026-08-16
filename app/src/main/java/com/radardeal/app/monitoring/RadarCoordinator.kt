package com.radardeal.app.monitoring

import android.os.SystemClock
import com.radardeal.app.core.RdLog
import com.radardeal.app.data.prefs.SettingsStore
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.domain.model.Watch
import com.radardeal.app.web.LiveDomChannel
import com.radardeal.app.web.LiveDomEvent
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
 * Decides *when* each watch is scanned, and guarantees that only one scan runs at a time.
 *
 * Three properties matter:
 *
 * **Single flight.** Every entry point — the service loop, "Scanner maintenant", the watchdog
 * worker — goes through the same mutex. The previous version had a hole: `scanNow` took no
 * lock, so a manual scan could run concurrently with a scheduled one and both would diff the
 * same watch against the same stored rows.
 *
 * **Adaptive interval.** A watch that keeps producing results is polled at its floor; one that
 * has been quiet drifts towards a longer interval, and snaps straight back to the floor the
 * moment something new appears. This buys back most of the battery cost of Ultra without
 * delaying the case the user actually cares about.
 *
 * **Refusal is respected.** A verification wall stops the watch outright rather than slowing it
 * down. Rate limiting backs off hard. RadarDeal never keeps hammering a site that is saying no.
 */
class RadarCoordinator(
    private val watchRepository: WatchRepository,
    private val scanEngine: ScanEngine,
    private val settingsStore: SettingsStore,
    private val knownIds: KnownIdCache,
    private val liveDom: LiveDomChannel,
) {

    /** The single flight lock. Nothing scans without holding it. */
    private val scanMutex = Mutex()

    /** watchId → monotonic millis before which the watch must not be scanned again. */
    private val nextDueAt = ConcurrentHashMap<Long, Long>()

    /** watchId → current adaptive multiplier applied to the base interval. */
    private val idleFactor = ConcurrentHashMap<Long, Double>()

    /** watchId → consecutive-error back-off step. */
    private val errorBackoff = ConcurrentHashMap<Long, Int>()

    /** watchId → monotonic millis of the last full resync. */
    private val lastResyncAt = ConcurrentHashMap<Long, Long>()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastTickAt = MutableStateFlow<Long?>(null)
    val lastTickAt: StateFlow<Long?> = _lastTickAt.asStateFlow()

    // --- Live channel ----------------------------------------------------------------------------

    /**
     * Keeps the live DOM channel parked on the Ultra watch, and only on it. One persistent
     * WebView is a reasonable cost for the search the user declared most important; ten would
     * not be.
     */
    suspend fun syncLiveChannel() {
        if (!liveDom.isSupported) return

        val ultra = runCatching { watchRepository.getUltraWatch() }.getOrNull()
            ?.takeIf { it.isActive && !it.lastScanStatus.haltsScanning }

        if (ultra == null) {
            if (liveDom.isAttached) liveDom.detach()
            return
        }
        if (liveDom.attachedWatchId == ultra.id) return

        val settings = runCatching { settingsStore.settings.first() }.getOrNull() ?: return
        val endpoints = VintedQuery.endpointsFor(ultra, settings.vintedHost)

        liveDom.attach(ultra.id, endpoints.host, endpoints.browseUrl) { event ->
            onLiveEvent(ultra, event)
        }
    }

    /** Callback invoked on the WebView's thread; hands work straight to the engine. */
    private fun onLiveEvent(watch: Watch, event: LiveDomEvent) {
        when (event) {
            is LiveDomEvent.Ready ->
                RdLog.d("Radar", "live channel ready, baseline ${event.baselineCount} cards")

            LiveDomEvent.Challenge -> {
                RdLog.i("Radar", "live channel saw a verification wall; pausing watch ${watch.id}")
                liveEventSink?.invoke(watch.id, emptyList(), true)
            }

            is LiveDomEvent.NewItem ->
                liveEventSink?.invoke(watch.id, listOf(event), false)
        }
    }

    /**
     * Set by the service, which owns a coroutine scope able to run suspending work. The
     * coordinator itself deliberately holds no scope so its lifetime stays trivial.
     */
    var liveEventSink: ((Long, List<LiveDomEvent.NewItem>, Boolean) -> Unit)? = null

    /** Handles a batch of live detections; called from the service's scope. */
    suspend fun processLiveDetections(watchId: Long, events: List<LiveDomEvent.NewItem>): Int {
        if (events.isEmpty()) return 0
        val watch = runCatching { watchRepository.getWatch(watchId) }.getOrNull() ?: return 0
        val settings = runCatching { settingsStore.settings.first() }.getOrNull() ?: return 0

        return scanEngine.acceptLiveDetections(
            watch = watch,
            settings = settings,
            incoming = events.map { it.listing },
            observedAtMs = events.minOf { it.observedAtMs },
        )
    }

    /** A verification wall seen by the live channel pauses the watch exactly like a poll would. */
    suspend fun pauseForVerification(watchId: Long) {
        runCatching { watchRepository.recordScan(watchId, ScanStatus.PAUSED_VERIFICATION) }
        nextDueAt[watchId] = Long.MAX_VALUE
        if (liveDom.attachedWatchId == watchId) liveDom.detach()
    }

    // --- Public API ----------------------------------------------------------------------------

    /**
     * Scans whichever watches are due. Returns immediately when another scan is already in
     * flight — a skipped tick is correct behaviour, since the in-flight scan is about to
     * refresh the same data. There is no queue to grow unbounded.
     */
    suspend fun tick(): TickResult {
        if (!scanMutex.tryLock()) {
            RdLog.d("Radar", "tick skipped, a scan is already running")
            return TickResult()
        }
        try {
            return runDueScans()
        } finally {
            scanMutex.unlock()
        }
    }

    /** "Scanner maintenant" — waits for any in-flight scan rather than racing it. */
    suspend fun scanNow(watchId: Long): ScanReport? = scanMutex.withLock {
        val watch = runCatching { watchRepository.getWatch(watchId) }.getOrNull() ?: return null
        val settings = runCatching { settingsStore.settings.first() }.getOrNull() ?: return null

        _isScanning.value = true
        try {
            // A manual scan is also the user's way of retrying after a verification pause.
            val report = scanEngine.scan(watch, settings, DetectionSource.MANUAL, fullResync = true)
            lastResyncAt[watchId] = elapsed()
            reschedule(watch, report)
            report
        } finally {
            _isScanning.value = false
            _lastTickAt.value = System.currentTimeMillis()
        }
    }

    /** Milliseconds until the next watch becomes due; drives how long the service sleeps. */
    suspend fun millisUntilNextDue(): Long {
        val active = runCatching { watchRepository.getActive() }.getOrDefault(emptyList())
        if (active.isEmpty()) return IDLE_SLEEP_MS

        val now = elapsed()
        val soonest = active
            .filterNot { it.lastScanStatus.haltsScanning }
            .minOfOrNull { (nextDueAt[it.id] ?: 0L) - now }
            ?: return IDLE_SLEEP_MS

        return soonest.coerceIn(0L, IDLE_SLEEP_MS)
    }

    /** The interval the fastest active watch is currently running at, for diagnostics. */
    suspend fun currentIntervalMs(): Long {
        val active = runCatching { watchRepository.getActive() }.getOrDefault(emptyList())
        return active.minOfOrNull { effectiveIntervalMs(it) } ?: IDLE_SLEEP_MS
    }

    fun markDueNow(watchId: Long) {
        nextDueAt[watchId] = 0L
        idleFactor.remove(watchId)
        errorBackoff.remove(watchId)
    }

    fun forget(watchId: Long) {
        nextDueAt.remove(watchId)
        idleFactor.remove(watchId)
        errorBackoff.remove(watchId)
        lastResyncAt.remove(watchId)
        knownIds.forget(watchId)
    }

    /** Called after a watch's criteria change: its collected ids no longer describe the search. */
    fun invalidate(watchId: Long) {
        knownIds.forget(watchId)
        markDueNow(watchId)
    }

    // --- Scheduling ------------------------------------------------------------------------------

    private suspend fun runDueScans(): TickResult {
        val settings = runCatching { settingsStore.settings.first() }.getOrNull()
            ?: return TickResult()

        val active = runCatching { watchRepository.getActive() }.getOrElse {
            RdLog.w("Radar", "could not read watches", it)
            emptyList()
        }

        val now = elapsed()
        val due = active
            // A watch paused on a verification makes no request at all until the user acts.
            .filterNot { it.lastScanStatus.haltsScanning }
            .filter { (nextDueAt[it.id] ?: 0L) <= now }
            // Ultra first: its whole purpose is not to queue behind slower watches.
            .sortedByDescending { it.isUltra }

        if (due.isEmpty()) return TickResult()

        _isScanning.value = true
        var newListings = 0
        var priceDrops = 0
        val problems = mutableListOf<ScanStatus>()

        try {
            for (watch in due) {
                val resync = shouldResync(watch, now)
                val report = scanEngine.scan(
                    watch = watch,
                    settings = settings,
                    source = if (resync) DetectionSource.RESYNC else DetectionSource.POLL,
                    fullResync = resync,
                )
                if (resync) lastResyncAt[watch.id] = elapsed()

                // Keep the parked page in step with the poll so its observer has something to
                // see; this replaces a full reload, which would discard the observer itself.
                if (watch.isUltra && liveDom.attachedWatchId == watch.id) {
                    runCatching { liveDom.softRefresh() }
                }

                newListings += report.newCount
                priceDrops += report.priceDropCount
                if (report.status.isProblem) problems += report.status
                reschedule(watch, report)
            }
        } finally {
            _isScanning.value = false
            _lastTickAt.value = System.currentTimeMillis()
            RadarPerf.recordInterval(currentIntervalMs())
        }

        return TickResult(due.size, newListings, priceDrops, problems)
    }

    /**
     * A periodic full pass catches whatever the incremental path missed: a listing that slipped
     * between two scans, a price change, a card the live channel never reported.
     */
    private fun shouldResync(watch: Watch, nowMs: Long): Boolean {
        val last = lastResyncAt[watch.id] ?: return true
        return nowMs - last >= RESYNC_INTERVAL_MS
    }

    private suspend fun reschedule(watch: Watch, report: ScanReport) {
        val status = report.status

        // Vinted is explicitly refusing. Stop this watch and tell the user; do not retry.
        if (status == ScanStatus.NEEDS_VERIFICATION) {
            RdLog.i("Radar", "watch ${watch.id} paused pending a Vinted verification")
            runCatching { watchRepository.recordScan(watch.id, ScanStatus.PAUSED_VERIFICATION) }
            nextDueAt[watch.id] = Long.MAX_VALUE
            return
        }

        val delayMs = when (status) {
            ScanStatus.RATE_LIMITED -> {
                // 429 gets a much longer pause than an ordinary error.
                val step = bumpBackoff(watch.id)
                (RATE_LIMIT_BASE_MS * (1L shl (step - 1).coerceAtMost(4)))
                    .coerceAtMost(MAX_BACKOFF_MS)
            }

            ScanStatus.NETWORK_ERROR, ScanStatus.PARSE_ERROR -> {
                val step = bumpBackoff(watch.id)
                (ERROR_BACKOFF_STEPS_MS.getOrNull(step - 1) ?: MAX_BACKOFF_MS)
            }

            ScanStatus.NEEDS_LOGIN -> LOGIN_RETRY_MS

            else -> {
                errorBackoff.remove(watch.id)
                adaptiveIntervalMs(watch, report)
            }
        }

        nextDueAt[watch.id] = elapsed() + delayMs
    }

    /**
     * Adaptive polling: the floor while a watch is productive, drifting up to a cap while it is
     * quiet, and back to the floor the instant something new arrives.
     */
    private fun adaptiveIntervalMs(watch: Watch, report: ScanReport): Long {
        val floor = watch.baseIntervalMs
        val ceiling = if (watch.isUltra) ULTRA_CEILING_MS else (floor * IDLE_CEILING_MULTIPLIER)

        val factor = if (report.newCount > 0) {
            idleFactor.remove(watch.id)
            1.0
        } else {
            val next = ((idleFactor[watch.id] ?: 1.0) * IDLE_GROWTH).coerceAtMost(
                ceiling.toDouble() / floor.coerceAtLeast(1L),
            )
            idleFactor[watch.id] = next
            next
        }

        return (floor * factor).toLong().coerceIn(floor, ceiling)
    }

    private fun effectiveIntervalMs(watch: Watch): Long {
        val floor = watch.baseIntervalMs
        val ceiling = if (watch.isUltra) ULTRA_CEILING_MS else floor * IDLE_CEILING_MULTIPLIER
        return (floor * (idleFactor[watch.id] ?: 1.0)).toLong().coerceIn(floor, ceiling)
    }

    private fun bumpBackoff(watchId: Long): Int {
        val next = ((errorBackoff[watchId] ?: 0) + 1).coerceAtMost(ERROR_BACKOFF_STEPS_MS.size)
        errorBackoff[watchId] = next
        return next
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime()

    private companion object {
        /** Ultra never drifts beyond this even when nothing is happening. */
        const val ULTRA_CEILING_MS = 5_000L

        /** Non-Ultra watches may drift to this multiple of their configured interval. */
        const val IDLE_CEILING_MULTIPLIER = 4L
        const val IDLE_GROWTH = 1.25

        /** 2.5 s → 5 s → 10 s → 30 s, as specified. */
        val ERROR_BACKOFF_STEPS_MS = longArrayOf(2_500, 5_000, 10_000, 30_000)
        const val RATE_LIMIT_BASE_MS = 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
        const val LOGIN_RETRY_MS = 5 * 60_000L

        /** Full re-analysis cadence, catching anything the incremental path missed. */
        const val RESYNC_INTERVAL_MS = 90_000L

        /** How long the service may sleep when nothing is scheduled. */
        const val IDLE_SLEEP_MS = 30_000L
    }
}
