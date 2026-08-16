package com.radardeal.app.monitoring

import android.os.SystemClock
import com.radardeal.app.core.RdLog
import com.radardeal.app.data.prefs.AppSettings
import com.radardeal.app.data.repository.ListingRepository
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.Listing
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.domain.model.Watch
import com.radardeal.app.notifications.RadarNotifications
import com.radardeal.app.web.FetchOutcome
import com.radardeal.app.web.VintedFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one scan of one watch produced. */
data class ScanReport(
    val watchId: Long,
    val status: ScanStatus,
    val parsedCount: Int = 0,
    val newCount: Int = 0,
    val priceDropCount: Int = 0,
    val wasBaseline: Boolean = false,
    /** Milliseconds from "ask Vinted" to "notification posted". -1 when nothing was new. */
    val detectionLatencyMs: Long = -1L,
) {
    val succeeded: Boolean get() = status == ScanStatus.OK || status == ScanStatus.EMPTY
}

/**
 * Runs one scan: fetch → pre-filter → notify → persist → enrich.
 *
 * **The ordering is the point.** The previous engine read the whole listings table, diffed
 * everything, wrote to the database, trimmed it, read every price back and computed a median —
 * and only then posted the notification. That put two database round trips and a statistical
 * calculation between a listing appearing and the user's phone buzzing.
 *
 * Now the hot path is:
 *
 *  1. ask Vinted;
 *  2. pull the ids out of the response and ask the in-memory [KnownIdCache] which are new —
 *     when the answer is "none", which is the overwhelming majority of scans, the scan ends
 *     here having allocated almost nothing;
 *  3. claim the new ids, build only those listings, **notify immediately**;
 *  4. persist, diff for price changes and compute deal ratings afterwards, off the critical
 *     path.
 *
 * A notification is therefore never delayed by storage or statistics. The deal badge appears a
 * few dozen milliseconds later, on the card.
 */
class ScanEngine(
    private val fetcher: VintedFetcher,
    private val watchRepository: WatchRepository,
    private val listingRepository: ListingRepository,
    private val notifications: RadarNotifications,
    private val knownIds: KnownIdCache,
    /** Scope for the after-the-fact work: persistence, price diffing, enrichment. */
    private val backgroundScope: CoroutineScope,
) {

    suspend fun scan(
        watch: Watch,
        settings: AppSettings,
        source: DetectionSource = DetectionSource.POLL,
        fullResync: Boolean = false,
    ): ScanReport = try {
        scanInternal(watch, settings, source, fullResync)
    } catch (t: Throwable) {
        RdLog.e("Scan", "watch ${watch.id} failed unexpectedly", t)
        RadarPerf.recordError(t.javaClass.simpleName)
        runCatching { watchRepository.recordScan(watch.id, ScanStatus.PARSE_ERROR) }
        ScanReport(watch.id, ScanStatus.PARSE_ERROR)
    }

    private suspend fun scanInternal(
        watch: Watch,
        settings: AppSettings,
        source: DetectionSource,
        fullResync: Boolean,
    ): ScanReport {
        val trace = RadarPerf.startTrace(watch.id, source, now())

        // The cache is loaded from Room once per watch, then never again on the hot path.
        ensureCacheWarm(watch.id)

        val endpoints = VintedQuery.endpointsFor(watch, settings.vintedHost)
        val outcome = fetcher.fetch(endpoints.host, endpoints.apiUrl, endpoints.browseUrl)
        trace.responseAt = now()

        if (outcome !is FetchOutcome.Success) {
            val status = outcome.toScanStatus()
            watchRepository.recordScan(watch.id, status)
            RadarPerf.record(trace, now())
            RadarPerf.recordError(status.name)
            return ScanReport(watch.id, status)
        }

        val parsed = withContext(Dispatchers.Default) {
            VintedParser.parse(outcome.body, endpoints.host)
        }
        trace.parsedAt = now()
        trace.cardsSeen = parsed.size

        if (parsed.isEmpty()) {
            return handleEmpty(watch, outcome.body, trace)
        }

        // --- The fast path -------------------------------------------------------------------
        // One hash lookup per card. No database, no object graph, no diff.
        val unknownIds = knownIds.filterUnknown(watch.id, parsed.map { it.itemId })

        if (unknownIds.isEmpty() && !fullResync) {
            watchRepository.recordScan(watch.id, ScanStatus.OK)
            RadarPerf.record(trace, now())
            return ScanReport(watch.id, ScanStatus.OK, parsedCount = parsed.size)
        }

        // Claim ids atomically so the live DOM channel and a poll cannot both announce the
        // same listing.
        val claimed = unknownIds.filter { knownIds.claim(watch.id, it) }.toSet()
        val byId = parsed.associateBy { it.itemId }
        val freshListings = claimed.mapNotNull { id -> byId[id]?.toListing(watch.id) }
        trace.detectedAt = now()
        trace.newCards = freshListings.size

        val isBaseline = !watch.baselineDone

        // --- Notify before doing anything durable ---------------------------------------------
        if (!isBaseline && freshListings.isNotEmpty()) {
            notifyNow(watch, settings, freshListings)
            trace.notifiedAt = now()
        }

        val latency = trace.totalDetectionLatency
        RadarPerf.record(trace, now())

        // --- Everything that can wait ----------------------------------------------------------
        val priceDrops = persistAndEnrich(watch, settings, parsed, isBaseline, freshListings)

        RdLog.i(
            "Scan",
            "watch ${watch.id} finished: ${parsed.size} parsed, " +
                "${if (isBaseline) 0 else freshListings.size} new (source=$source)",
        )

        return ScanReport(
            watchId = watch.id,
            status = ScanStatus.OK,
            parsedCount = parsed.size,
            newCount = if (isBaseline) 0 else freshListings.size,
            priceDropCount = priceDrops,
            wasBaseline = isBaseline,
            detectionLatencyMs = latency,
        )
    }

    /**
     * Detection reported by the live DOM channel: the ids already arrived, so there is no
     * fetch, no parse and no scheduling — just claim, notify, persist.
     */
    suspend fun acceptLiveDetections(
        watch: Watch,
        settings: AppSettings,
        incoming: List<RawListing>,
        observedAtMs: Long,
    ): Int {
        if (incoming.isEmpty()) return 0
        ensureCacheWarm(watch.id)

        val trace = RadarPerf.startTrace(watch.id, DetectionSource.LIVE_DOM, observedAtMs).apply {
            responseAt = observedAtMs
            parsedAt = observedAtMs
            cardsSeen = incoming.size
        }

        val claimed = incoming.filter { knownIds.claim(watch.id, it.itemId) }
        trace.detectedAt = now()
        trace.newCards = claimed.size

        if (claimed.isEmpty()) {
            RadarPerf.record(trace, now())
            return 0
        }

        val listings = claimed.map { it.toListing(watch.id) }
        if (watch.baselineDone) {
            notifyNow(watch, settings, listings)
            trace.notifiedAt = now()
        }
        RadarPerf.record(trace, now())

        backgroundScope.launch(Dispatchers.IO) {
            runCatching {
                listingRepository.persistScanResults(
                    entities = listings.map { it.copy(isNew = watch.baselineDone).toEntityRow() },
                    pricePoints = emptyList(),
                )
            }.onFailure { RdLog.w("Scan", "live persist failed", it) }
        }

        RdLog.i("Scan", "watch ${watch.id}: ${claimed.size} new via live DOM")
        return claimed.size
    }

    // --- Internals --------------------------------------------------------------------------

    private suspend fun ensureCacheWarm(watchId: Long) {
        if (knownIds.isWarm(watchId)) return
        val ids = runCatching { watchRepository.knownItemIds(watchId) }.getOrDefault(emptyList())
        knownIds.warm(watchId, ids)
        RdLog.d("Scan", "id cache warmed for watch $watchId (${ids.size} ids)")
    }

    private suspend fun handleEmpty(
        watch: Watch,
        body: String,
        trace: ScanTrace,
    ): ScanReport {
        // Distinguish "Vinted answered, nothing matches" from "we could not read it".
        val status = if (body.contains("\"items\"")) ScanStatus.EMPTY else ScanStatus.PARSE_ERROR
        watchRepository.recordScan(watch.id, status)
        if (status == ScanStatus.EMPTY && !watch.baselineDone) {
            watchRepository.markBaselineDone(watch.id)
        }
        RadarPerf.record(trace, now())
        return ScanReport(watch.id, status, wasBaseline = !watch.baselineDone)
    }

    private fun notifyNow(watch: Watch, settings: AppSettings, listings: List<Listing>) {
        if (!settings.notificationsEnabled || !watch.notifyEnabled) return
        // Deliberately without the median: the deal badge is computed later and shown on the
        // card. Waiting for a statistic before buzzing would defeat the whole engine.
        runCatching { notifications.notifyNewListings(watch, listings, emptyList(), settings) }
            .onFailure { RdLog.w("Scan", "notification failed", it) }
    }

    /**
     * Persistence, price-drop detection and trimming. Runs after the notification and, for the
     * incremental path, does not block the caller.
     */
    private suspend fun persistAndEnrich(
        watch: Watch,
        settings: AppSettings,
        parsed: List<RawListing>,
        isBaseline: Boolean,
        freshListings: List<Listing>,
    ): Int = withContext(Dispatchers.IO) {
        runCatching {
            // The full diff needs the stored rows, but this read is no longer on the path
            // between a listing appearing and the user being told about it.
            val known = listingRepository.getForWatch(watch.id).associateBy { it.itemId }

            val diff = ScanDiff.diff(
                watchId = watch.id,
                known = known,
                incoming = parsed,
                baselineDone = watch.baselineDone,
            )

            // Newly claimed listings must be stored as new even though the diff sees them as
            // unknown-and-therefore-baseline when the cache claimed them first.
            val freshIds = freshListings.mapTo(HashSet()) { it.itemId }
            val entities = diff.entities.map { entity ->
                if (!isBaseline && entity.itemId in freshIds) entity.copy(isNew = true) else entity
            }

            listingRepository.persistScanResults(entities, diff.pricePoints)
            listingRepository.trim(watch.id)
            knownIds.addAll(watch.id, parsed.map { it.itemId })

            if (isBaseline) {
                watchRepository.markBaselineDone(watch.id)
                RdLog.i("Scan", "watch ${watch.id} baseline recorded (${entities.size} listings)")
            }
            watchRepository.recordScan(watch.id, ScanStatus.OK)

            // Price drops are announced here rather than on the detection path: a new listing
            // always matters more than a price change on one already seen.
            if (!isBaseline && diff.priceDrops.isNotEmpty() &&
                settings.notificationsEnabled && watch.notifyEnabled
            ) {
                runCatching { notifications.notifyPriceDrops(watch, diff.priceDrops, settings) }
            }
            diff.priceDrops.size
        }.getOrElse {
            RdLog.w("Scan", "enrichment failed for watch ${watch.id}", it)
            0
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}

/** Minimal projection used on the hot path — no database row, no statistics. */
internal fun RawListing.toListing(watchId: Long, nowMs: Long = System.currentTimeMillis()) = Listing(
    itemId = itemId,
    watchId = watchId,
    title = title,
    brand = brand,
    size = size,
    condition = condition,
    price = price,
    currency = currency,
    imageUrl = imageUrl,
    itemUrl = itemUrl,
    firstSeenAt = nowMs,
    lastSeenAt = nowMs,
    isNew = true,
)

private fun Listing.toEntityRow() = com.radardeal.app.data.local.ListingEntity(
    watchId = watchId,
    itemId = itemId,
    title = title,
    brand = brand,
    size = size,
    condition = condition,
    price = price,
    currency = currency,
    previousPrice = previousPrice,
    imageUrl = imageUrl,
    itemUrl = itemUrl,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    isNew = isNew,
    isFavorite = isFavorite,
    priceDroppedAt = priceDroppedAt,
)
