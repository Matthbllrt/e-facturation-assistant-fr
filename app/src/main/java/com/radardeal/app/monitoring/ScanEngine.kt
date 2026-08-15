package com.radardeal.app.monitoring

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

/** What one scan of one watch produced. */
data class ScanReport(
    val watchId: Long,
    val status: ScanStatus,
    val parsedCount: Int = 0,
    val newCount: Int = 0,
    val priceDropCount: Int = 0,
    val wasBaseline: Boolean = false,
) {
    val succeeded: Boolean get() = status == ScanStatus.OK || status == ScanStatus.EMPTY
}

/**
 * Runs one scan: fetch → parse → diff → persist → notify.
 *
 * The whole method is wrapped so that no scan can ever propagate an exception into the
 * foreground service loop; the worst case is a watch marked [ScanStatus.PARSE_ERROR] that will
 * simply be retried on the next tick.
 */
class ScanEngine(
    private val fetcher: VintedFetcher,
    private val watchRepository: WatchRepository,
    private val listingRepository: ListingRepository,
    private val notifications: RadarNotifications,
) {

    suspend fun scan(watch: Watch, settings: AppSettings): ScanReport = try {
        scanInternal(watch, settings)
    } catch (t: Throwable) {
        RdLog.e("Scan", "watch ${watch.id} failed unexpectedly", t)
        runCatching { watchRepository.recordScan(watch.id, ScanStatus.PARSE_ERROR) }
        ScanReport(watch.id, ScanStatus.PARSE_ERROR)
    }

    private suspend fun scanInternal(watch: Watch, settings: AppSettings): ScanReport {
        RdLog.i("Scan", "watch ${watch.id} started (${watch.intervalSeconds}s interval)")

        val endpoints = VintedQuery.endpointsFor(watch, settings.vintedHost)
        val outcome = fetcher.fetch(endpoints.host, endpoints.apiUrl, endpoints.browseUrl)

        if (outcome !is FetchOutcome.Success) {
            val status = outcome.toScanStatus()
            RdLog.i("Scan", "watch ${watch.id} finished: $status")
            watchRepository.recordScan(watch.id, status)
            return ScanReport(watch.id, status)
        }

        RdLog.d("Scan", "URL loaded via ${outcome.via}")

        val parsed = VintedParser.parse(outcome.body, endpoints.host)
        RdLog.d("Scan", "${parsed.size} cards parsed")

        if (parsed.isEmpty()) {
            // Distinguish "Vinted answered, nothing matches" from "we could not read it".
            val status = if (outcome.body.contains("\"items\"")) {
                ScanStatus.EMPTY
            } else {
                ScanStatus.PARSE_ERROR
            }
            watchRepository.recordScan(watch.id, status)
            // An empty result still counts as a baseline: the search simply has no results yet.
            if (status == ScanStatus.EMPTY && !watch.baselineDone) {
                watchRepository.markBaselineDone(watch.id)
            }
            RdLog.i("Scan", "watch ${watch.id} finished: $status")
            return ScanReport(watch.id, status, parsedCount = 0, wasBaseline = !watch.baselineDone)
        }

        val known: Map<String, Listing> = listingRepository.getForWatch(watch.id)
            .associateBy { it.itemId }

        val diff = ScanDiff.diff(
            watchId = watch.id,
            known = known,
            incoming = parsed,
            baselineDone = watch.baselineDone,
        )

        listingRepository.persistScanResults(diff.entities, diff.pricePoints)
        listingRepository.trim(watch.id)

        if (!watch.baselineDone) {
            watchRepository.markBaselineDone(watch.id)
            RdLog.i(
                "Scan",
                "watch ${watch.id} baseline recorded with ${diff.entities.size} listings"
            )
        }

        watchRepository.recordScan(watch.id, ScanStatus.OK)

        notifyIfNeeded(watch, settings, diff)

        RdLog.i(
            "Scan",
            "watch ${watch.id} finished: ${parsed.size} parsed, " +
                "${diff.newListings.size} new, ${diff.priceDrops.size} price drops"
        )

        return ScanReport(
            watchId = watch.id,
            status = ScanStatus.OK,
            parsedCount = parsed.size,
            newCount = diff.newListings.size,
            priceDropCount = diff.priceDrops.size,
            wasBaseline = !watch.baselineDone,
        )
    }

    private suspend fun notifyIfNeeded(watch: Watch, settings: AppSettings, diff: ScanDiff.Outcome) {
        if (!settings.notificationsEnabled || !watch.notifyEnabled) return
        if (diff.newListings.isEmpty() && diff.priceDrops.isEmpty()) return

        // Rate the newest listings against everything known for this watch, so the
        // notification can carry the same "sous la médiane" context as the card.
        val prices = runCatching { listingRepository.getPricesForWatch(watch.id) }
            .getOrDefault(emptyList())

        runCatching {
            notifications.notifyNewListings(watch, diff.newListings, prices, settings)
            notifications.notifyPriceDrops(watch, diff.priceDrops, settings)
        }.onFailure { RdLog.w("Scan", "notification failed", it) }
    }
}
