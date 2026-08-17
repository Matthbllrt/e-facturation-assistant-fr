import Foundation
import RadarDealCore
import UserNotifications

/// What one scan of one watch produced.
struct ScanReport {
    let watchID: Int64
    let status: ScanStatus
    var parsedCount = 0
    var newCount = 0
    var priceDropCount = 0
    var wasBaseline = false
    /// Milliseconds from "ask Vinted" to "notification posted". Nil when nothing was new.
    var detectionLatencyMs: Double?
}

/// The engine, ordered so a notification is never delayed by storage or statistics.
///
/// The hot path is identical to Android's:
/// 1. ask Vinted;
/// 2. pull the ids out and ask the in-memory `KnownIDCache` which are new — when the answer is
///    "none", which is the overwhelming majority of scans, the scan ends here;
/// 3. claim the new ids, build only those listings, **notify immediately**;
/// 4. persist, diff for price changes and compute deal ratings afterwards.
@MainActor
final class RadarEngine {

    private let store: Store
    private let fetcher: VintedFetcher
    private let notifications: Notifier
    private let knownIDs: KnownIDCache
    private let settings: SettingsStore

    init(
        store: Store,
        fetcher: VintedFetcher,
        notifications: Notifier,
        knownIDs: KnownIDCache,
        settings: SettingsStore
    ) {
        self.store = store
        self.fetcher = fetcher
        self.notifications = notifications
        self.knownIDs = knownIDs
        self.settings = settings
    }

    func scan(_ watch: Watch, fullResync: Bool = false) async -> ScanReport {
        let startedAt = Date()

        // The cache is loaded from storage once per watch, then never again on the hot path.
        if !knownIDs.isWarm(watch.id) {
            knownIDs.warm(watch.id, ids: store.knownItemIDs(watchID: watch.id))
            RDLog.debug("id cache warmed for watch \(watch.id) (\(knownIDs.size(watch.id)) ids)")
        }

        let endpoints = VintedQuery.endpoints(for: watch, fallbackHost: settings.vintedHost)
        let outcome = await fetcher.fetch(
            host: endpoints.host, apiURL: endpoints.apiURL, referer: endpoints.browseURL
        )

        guard case let .success(body) = outcome else {
            let status = outcome.asStatus
            store.recordScan(id: watch.id, status: status)
            return ScanReport(watchID: watch.id, status: status)
        }

        // Parsing is pure and can be expensive on a large page: keep it off the main actor.
        let parsed = await Task.detached(priority: .userInitiated) {
            VintedParser.parse(body, host: endpoints.host)
        }.value

        guard !parsed.isEmpty else {
            // Distinguish "Vinted answered, nothing matches" from "we could not read it".
            let status: ScanStatus = body.contains("\"items\"") ? .empty : .parseError
            store.recordScan(id: watch.id, status: status)
            if status == .empty && !watch.baselineDone { store.markBaselineDone(id: watch.id) }
            return ScanReport(watchID: watch.id, status: status,
                              wasBaseline: !watch.baselineDone)
        }

        // --- The fast path: one hash lookup per card, no storage, no diff ---------------------
        let unknown = knownIDs.filterUnknown(watch.id, parsed.map(\.itemID))

        if unknown.isEmpty && !fullResync {
            store.recordScan(id: watch.id, status: .ok)
            return ScanReport(watchID: watch.id, status: .ok, parsedCount: parsed.count)
        }

        let claimed = Set(unknown.filter { knownIDs.claim(watch.id, $0) })
        let byID = Dictionary(parsed.map { ($0.itemID, $0) }, uniquingKeysWith: { a, _ in a })
        let fresh = claimed.compactMap { byID[$0]?.toListing(watchID: watch.id) }
        let isBaseline = !watch.baselineDone

        // --- Notify before doing anything durable ----------------------------------------------
        var latency: Double?
        if !isBaseline, !fresh.isEmpty,
           settings.notificationsEnabled, watch.notifyEnabled {
            // Deliberately without the median: the deal badge is computed later and shown on
            // the card. Waiting for a statistic before buzzing would defeat the engine.
            await notifications.notifyNewListings(watch: watch, listings: fresh)
            latency = Date().timeIntervalSince(startedAt) * 1000
        }

        let drops = persistAndEnrich(
            watch: watch, parsed: parsed, isBaseline: isBaseline, fresh: fresh
        )

        RDLog.debug(
            "watch \(watch.id) finished: \(parsed.count) parsed, "
            + "\(isBaseline ? 0 : fresh.count) new"
        )

        return ScanReport(
            watchID: watch.id,
            status: .ok,
            parsedCount: parsed.count,
            newCount: isBaseline ? 0 : fresh.count,
            priceDropCount: drops,
            wasBaseline: isBaseline,
            detectionLatencyMs: latency
        )
    }

    /// Persistence, price-drop detection and trimming — after the notification, never before.
    private func persistAndEnrich(
        watch: Watch,
        parsed: [RawListing],
        isBaseline: Bool,
        fresh: [Listing]
    ) -> Int {
        let known = Dictionary(
            store.listings(watchID: watch.id).map { ($0.itemID, $0) },
            uniquingKeysWith: { a, _ in a }
        )

        let diff = ScanDiff.diff(
            watchID: watch.id, known: known, incoming: parsed, baselineDone: watch.baselineDone
        )

        // Newly claimed listings must be stored as new even though the diff sees them as
        // unknown-and-therefore-baseline when the cache claimed them first.
        let freshIDs = Set(fresh.map(\.itemID))
        let listings = diff.listings.map { listing -> Listing in
            guard !isBaseline, freshIDs.contains(listing.itemID) else { return listing }
            var copy = listing
            copy.isNew = true
            return copy
        }

        store.upsert(listings)
        store.appendPricePoints(watchID: watch.id, points: diff.pricePoints)
        store.trim(watchID: watch.id)
        knownIDs.addAll(watch.id, ids: parsed.map(\.itemID))

        if isBaseline {
            store.markBaselineDone(id: watch.id)
            RDLog.debug("watch \(watch.id) baseline recorded (\(listings.count) listings)")
        }
        store.recordScan(id: watch.id, status: .ok)

        // Price drops matter less than a new listing, so they are announced here.
        if !isBaseline, !diff.priceDrops.isEmpty,
           settings.notificationsEnabled, watch.notifyEnabled {
            Task { await notifications.notifyPriceDrops(watch: watch, drops: diff.priceDrops) }
        }
        return diff.priceDrops.count
    }
}
