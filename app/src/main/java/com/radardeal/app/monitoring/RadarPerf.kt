package com.radardeal.app.monitoring

import com.radardeal.app.BuildConfig
import com.radardeal.app.core.RdLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import kotlin.math.roundToLong

/** How a listing reached RadarDeal. */
enum class DetectionSource { POLL, LIVE_DOM, RESYNC, MANUAL }

/**
 * One measured detection, from the moment RadarDeal asked for data to the moment the user's
 * phone buzzed. All timestamps are `SystemClock.elapsedRealtime`-style monotonic millis
 * supplied by the caller, so a wall-clock change cannot produce a negative latency.
 */
data class ScanTrace(
    val watchId: Long,
    val source: DetectionSource,
    val requestedAt: Long,
    var responseAt: Long = 0L,
    var parsedAt: Long = 0L,
    var detectedAt: Long = 0L,
    var notifiedAt: Long = 0L,
    var persistedAt: Long = 0L,
    var cardsSeen: Int = 0,
    var newCards: Int = 0,
) {
    val requestToResponse: Long get() = delta(requestedAt, responseAt)
    val responseToParsed: Long get() = delta(responseAt, parsedAt)
    val parsedToDetected: Long get() = delta(parsedAt, detectedAt)
    val detectedToNotified: Long get() = delta(detectedAt, notifiedAt)

    /** The KPI: from asking Vinted to the notification leaving RadarDeal. */
    val totalDetectionLatency: Long get() = delta(requestedAt, notifiedAt)

    /** Cost of one scan cycle regardless of whether anything was new. */
    val scanDuration: Long
        get() = delta(requestedAt, maxOf(parsedAt, detectedAt, notifiedAt, persistedAt, responseAt))

    private fun delta(from: Long, to: Long): Long = if (from <= 0L || to <= 0L) -1L else to - from
}

/** A rolling snapshot of engine health, surfaced by the debug diagnostics panel. */
data class PerfSnapshot(
    val currentIntervalMs: Long = 0L,
    val lastScanDurationMs: Long = -1L,
    val averageScanDurationMs: Long = -1L,
    val lastDetectionLatencyMs: Long = -1L,
    val medianDetectionLatencyMs: Long = -1L,
    val cardsScanned: Long = 0L,
    val newCards: Long = 0L,
    val domMutations: Long = 0L,
    val liveDomDetections: Long = 0L,
    val pollDetections: Long = 0L,
    val scansLastHour: Int = 0,
    val webViewActive: Boolean = false,
    val lastError: String? = null,
)

/**
 * Engine instrumentation.
 *
 * Always collects — the counters are a handful of longs and the cost is irrelevant next to a
 * network round trip — but only *logs* in debug builds, and the diagnostics panel that renders
 * [snapshot] is compiled out of release. Nothing recorded here is user content: ids, titles and
 * prices never enter this file, only durations and counts.
 */
object RadarPerf {

    private const val WINDOW = 50

    private val scanDurations = ArrayDeque<Long>(WINDOW)
    private val detectionLatencies = ArrayDeque<Long>(WINDOW)
    private val scanTimestamps = ArrayDeque<Long>(240)

    private val _snapshot = MutableStateFlow(PerfSnapshot())
    val snapshot: StateFlow<PerfSnapshot> = _snapshot.asStateFlow()

    private var cardsScanned = 0L
    private var newCards = 0L
    private var domMutations = 0L
    private var liveDomDetections = 0L
    private var pollDetections = 0L

    fun startTrace(watchId: Long, source: DetectionSource, nowMs: Long): ScanTrace =
        ScanTrace(watchId = watchId, source = source, requestedAt = nowMs)

    fun recordDomMutation(count: Int = 1) = synchronized(this) {
        domMutations += count
        publish()
    }

    fun recordWebViewActive(active: Boolean) = synchronized(this) {
        _snapshot.value = _snapshot.value.copy(webViewActive = active)
    }

    fun recordInterval(intervalMs: Long) = synchronized(this) {
        _snapshot.value = _snapshot.value.copy(currentIntervalMs = intervalMs)
    }

    fun recordError(message: String?) = synchronized(this) {
        _snapshot.value = _snapshot.value.copy(lastError = message)
    }

    /** Called once a scan has fully finished, whether or not it found anything. */
    fun record(trace: ScanTrace, nowMs: Long) = synchronized(this) {
        cardsScanned += trace.cardsSeen
        newCards += trace.newCards

        when (trace.source) {
            DetectionSource.LIVE_DOM -> if (trace.newCards > 0) liveDomDetections += trace.newCards
            DetectionSource.POLL -> if (trace.newCards > 0) pollDetections += trace.newCards
            else -> Unit
        }

        push(scanTimestamps, nowMs, 240)
        val duration = trace.scanDuration
        if (duration >= 0) push(scanDurations, duration, WINDOW)

        val latency = trace.totalDetectionLatency
        if (latency >= 0 && trace.newCards > 0) push(detectionLatencies, latency, WINDOW)

        if (BuildConfig.VERBOSE_LOGGING && (trace.newCards > 0 || duration > 1_500)) {
            logTrace(trace)
        }

        publish()
    }

    private fun logTrace(trace: ScanTrace) {
        RdLog.d(
            "RadarPerf",
            buildString {
                append("source=").append(trace.source)
                append(" request_to_response=").append(trace.requestToResponse).append("ms")
                append(" response_to_parsed=").append(trace.responseToParsed).append("ms")
                append(" parsed_to_detection=").append(trace.parsedToDetected).append("ms")
                append(" detection_to_notification=").append(trace.detectedToNotified).append("ms")
                append(" total=").append(trace.totalDetectionLatency).append("ms")
                append(" cards=").append(trace.cardsSeen)
                append(" new=").append(trace.newCards)
            },
        )
    }

    private fun publish() {
        val hourAgo = (scanTimestamps.peekLast() ?: 0L) - 3_600_000L
        _snapshot.value = _snapshot.value.copy(
            lastScanDurationMs = scanDurations.peekLast() ?: -1L,
            averageScanDurationMs = scanDurations.average(),
            lastDetectionLatencyMs = detectionLatencies.peekLast() ?: -1L,
            medianDetectionLatencyMs = detectionLatencies.median(),
            cardsScanned = cardsScanned,
            newCards = newCards,
            domMutations = domMutations,
            liveDomDetections = liveDomDetections,
            pollDetections = pollDetections,
            scansLastHour = scanTimestamps.count { it >= hourAgo },
        )
    }

    private fun push(queue: ArrayDeque<Long>, value: Long, limit: Int) {
        queue.addLast(value)
        while (queue.size > limit) queue.removeFirst()
    }

    private fun ArrayDeque<Long>.average(): Long =
        if (isEmpty()) -1L else (sum().toDouble() / size).roundToLong()

    private fun ArrayDeque<Long>.median(): Long {
        if (isEmpty()) return -1L
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Used by tests and by "Effacer l'historique". */
    fun reset() = synchronized(this) {
        scanDurations.clear()
        detectionLatencies.clear()
        scanTimestamps.clear()
        cardsScanned = 0
        newCards = 0
        domMutations = 0
        liveDomDetections = 0
        pollDetections = 0
        _snapshot.value = PerfSnapshot()
    }
}
