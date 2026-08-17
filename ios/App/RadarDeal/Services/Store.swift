import Foundation
import SwiftData
import RadarDealCore

// MARK: - SwiftData models

/// A saved search. SwiftData is the iOS counterpart of Android's Room.
@Model
final class WatchEntity {
    #Unique<WatchEntity>([\.persistentID])

    var persistentID: Int64 = 0
    var name: String = ""
    var keyword: String?
    var brand: String?
    var minPrice: Double?
    var maxPrice: Double?
    var sourceURL: String?
    var intervalSeconds: Int = 15
    /// Single-slot fast mode; the invariant is enforced in `Store.grantUltra`.
    var isUltra: Bool = false
    var isActive: Bool = true
    var notifyEnabled: Bool = true
    var createdAt: Date = Date()
    var lastScanAt: Date?
    /// Stored as the raw value so an unknown future state degrades instead of crashing.
    var lastScanStatusRaw: String = ScanStatus.never.rawValue
    var baselineDone: Bool = false

    @Relationship(deleteRule: .cascade, inverse: \ListingEntity.watch)
    var listings: [ListingEntity] = []

    init(from watch: Watch) {
        persistentID = watch.id
        apply(watch)
    }

    func apply(_ watch: Watch) {
        name = watch.name
        keyword = watch.keyword
        brand = watch.brand
        minPrice = watch.minPrice
        maxPrice = watch.maxPrice
        sourceURL = watch.sourceURL
        intervalSeconds = watch.intervalSeconds
        isUltra = watch.isUltra
        isActive = watch.isActive
        notifyEnabled = watch.notifyEnabled
        createdAt = watch.createdAt
        lastScanAt = watch.lastScanAt
        lastScanStatusRaw = watch.lastScanStatus.rawValue
        baselineDone = watch.baselineDone
    }

    var asWatch: Watch {
        Watch(
            id: persistentID, name: name, keyword: keyword, brand: brand,
            minPrice: minPrice, maxPrice: maxPrice, sourceURL: sourceURL,
            intervalSeconds: intervalSeconds, isUltra: isUltra, isActive: isActive,
            notifyEnabled: notifyEnabled, createdAt: createdAt, lastScanAt: lastScanAt,
            lastScanStatus: ScanStatus(rawValue: lastScanStatusRaw) ?? .never,
            baselineDone: baselineDone
        )
    }
}

/// One listing, scoped to its watch — the same item found by two veilles is stored twice so
/// that "new", "favourite" and the per-watch median stay independent.
@Model
final class ListingEntity {
    var itemID: String = ""
    var watchID: Int64 = 0
    var title: String?
    var brand: String?
    var size: String?
    var condition: String?
    var price: Double?
    var currency: String?
    var previousPrice: Double?
    var imageURL: String?
    var itemURL: String?
    var firstSeenAt: Date = Date()
    var lastSeenAt: Date = Date()
    var isNew: Bool = false
    var isFavorite: Bool = false
    var priceDroppedAt: Date?

    var watch: WatchEntity?

    init(from listing: Listing) {
        itemID = listing.itemID
        watchID = listing.watchID
        apply(listing)
    }

    func apply(_ listing: Listing) {
        title = listing.title
        brand = listing.brand
        size = listing.size
        condition = listing.condition
        price = listing.price
        currency = listing.currency
        previousPrice = listing.previousPrice
        imageURL = listing.imageURL
        itemURL = listing.itemURL
        firstSeenAt = listing.firstSeenAt
        lastSeenAt = listing.lastSeenAt
        isNew = listing.isNew
        isFavorite = listing.isFavorite
        priceDroppedAt = listing.priceDroppedAt
    }

    var asListing: Listing {
        Listing(
            itemID: itemID, watchID: watchID, title: title, brand: brand, size: size,
            condition: condition, price: price, currency: currency,
            previousPrice: previousPrice, imageURL: imageURL, itemURL: itemURL,
            firstSeenAt: firstSeenAt, lastSeenAt: lastSeenAt, isNew: isNew,
            isFavorite: isFavorite, priceDroppedAt: priceDroppedAt
        )
    }
}

@Model
final class PricePointEntity {
    var watchID: Int64 = 0
    var itemID: String = ""
    var price: Double = 0
    var recordedAt: Date = Date()

    init(watchID: Int64, itemID: String, price: Double, recordedAt: Date) {
        self.watchID = watchID
        self.itemID = itemID
        self.price = price
        self.recordedAt = recordedAt
    }
}

// MARK: - Store

/// All persistence in one place, so the rest of the app never touches a ModelContext directly.
///
/// Runs on the main actor because SwiftData's `ModelContext` is not `Sendable`; the expensive
/// part of a scan (fetch, parse, diff) happens off-actor in `ScanEngine` and only the writes
/// come back here.
@MainActor
final class Store {

    let container: ModelContainer
    private var context: ModelContext { container.mainContext }

    /// How many listings are retained per watch before the oldest non-favourites are dropped.
    private let retentionPerWatch = 400

    init(inMemory: Bool = false) throws {
        let schema = Schema([WatchEntity.self, ListingEntity.self, PricePointEntity.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: inMemory)
        container = try ModelContainer(for: schema, configurations: [configuration])
    }

    // MARK: Watches

    func allWatches() -> [Watch] {
        let descriptor = FetchDescriptor<WatchEntity>(
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        return ((try? context.fetch(descriptor)) ?? []).map(\.asWatch)
    }

    func activeWatches() -> [Watch] { allWatches().filter(\.isActive) }

    func watch(id: Int64) -> Watch? { entity(id: id)?.asWatch }

    private func entity(id: Int64) -> WatchEntity? {
        let descriptor = FetchDescriptor<WatchEntity>(
            predicate: #Predicate { $0.persistentID == id }
        )
        return try? context.fetch(descriptor).first
    }

    @discardableResult
    func createWatch(_ watch: Watch) -> Int64 {
        // SwiftData has no autoincrement; allocate the next id ourselves.
        let nextID = (allWatches().map(\.id).max() ?? 0) + 1
        var copy = watch
        copy.id = nextID
        context.insert(WatchEntity(from: copy))
        save()
        return nextID
    }

    func updateWatch(_ watch: Watch) {
        entity(id: watch.id)?.apply(watch)
        save()
    }

    func deleteWatch(id: Int64) {
        guard let entity = entity(id: id) else { return }
        context.delete(entity)
        // Price points carry no relationship, so they are removed explicitly.
        let points = FetchDescriptor<PricePointEntity>(
            predicate: #Predicate { $0.watchID == id }
        )
        for point in (try? context.fetch(points)) ?? [] { context.delete(point) }
        save()
    }

    func setActive(id: Int64, active: Bool) {
        entity(id: id)?.isActive = active
        save()
    }

    func recordScan(id: Int64, status: ScanStatus, at date: Date = Date()) {
        guard let entity = entity(id: id) else { return }
        entity.lastScanAt = date
        entity.lastScanStatusRaw = status.rawValue
        save()
    }

    func markBaselineDone(id: Int64) {
        entity(id: id)?.baselineDone = true
        save()
    }

    func resetBaseline(id: Int64) {
        entity(id: id)?.baselineDone = false
        save()
    }

    // MARK: Ultra — single slot

    func ultraWatch() -> Watch? { allWatches().first { $0.isUltra } }

    /// Moves the single Ultra slot to `id`, revoking it from whoever held it. Both halves
    /// happen before one save, so two watches are never observable as Ultra at once.
    func grantUltra(id: Int64) {
        let descriptor = FetchDescriptor<WatchEntity>()
        for entity in (try? context.fetch(descriptor)) ?? [] {
            entity.isUltra = (entity.persistentID == id)
        }
        save()
    }

    func revokeUltra(id: Int64) {
        entity(id: id)?.isUltra = false
        save()
    }

    // MARK: Listings

    /// Ids only — this feeds the in-memory cache that keeps storage off the detection path.
    func knownItemIDs(watchID: Int64) -> [String] {
        var descriptor = FetchDescriptor<ListingEntity>(
            predicate: #Predicate { $0.watchID == watchID }
        )
        descriptor.propertiesToFetch = [\.itemID]
        return ((try? context.fetch(descriptor)) ?? []).map(\.itemID)
    }

    func listings(watchID: Int64) -> [Listing] {
        let descriptor = FetchDescriptor<ListingEntity>(
            predicate: #Predicate { $0.watchID == watchID }
        )
        return ((try? context.fetch(descriptor)) ?? []).map(\.asListing)
    }

    func recentListings(limit: Int = 600) -> [Listing] {
        var descriptor = FetchDescriptor<ListingEntity>(
            sortBy: [SortDescriptor(\.firstSeenAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return ((try? context.fetch(descriptor)) ?? []).map(\.asListing)
    }

    func favorites() -> [Listing] {
        let descriptor = FetchDescriptor<ListingEntity>(
            predicate: #Predicate { $0.isFavorite },
            sortBy: [SortDescriptor(\.firstSeenAt, order: .reverse)]
        )
        return ((try? context.fetch(descriptor)) ?? []).map(\.asListing)
    }

    func prices(watchID: Int64) -> [Double] {
        listings(watchID: watchID).compactMap(\.price)
    }

    func upsert(_ listings: [Listing]) {
        guard !listings.isEmpty else { return }
        let watchID = listings[0].watchID
        let existing = Dictionary(
            (fetchEntities(watchID: watchID)).map { ($0.itemID, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        for listing in listings {
            if let row = existing[listing.itemID] {
                row.apply(listing)
            } else {
                let row = ListingEntity(from: listing)
                row.watch = entity(id: listing.watchID)
                context.insert(row)
            }
        }
        save()
    }

    func appendPricePoints(watchID: Int64, points: [(itemID: String, point: PricePoint)]) {
        guard !points.isEmpty else { return }
        for entry in points {
            context.insert(
                PricePointEntity(
                    watchID: watchID, itemID: entry.itemID,
                    price: entry.point.price, recordedAt: entry.point.recordedAt
                )
            )
        }
        save()
    }

    func priceHistory(watchID: Int64, itemID: String) -> [PricePoint] {
        let descriptor = FetchDescriptor<PricePointEntity>(
            predicate: #Predicate { $0.watchID == watchID && $0.itemID == itemID },
            sortBy: [SortDescriptor(\.recordedAt)]
        )
        return ((try? context.fetch(descriptor)) ?? [])
            .map { PricePoint(price: $0.price, recordedAt: $0.recordedAt) }
    }

    func setFavorite(watchID: Int64, itemID: String, favorite: Bool) {
        row(watchID: watchID, itemID: itemID)?.isFavorite = favorite
        save()
    }

    func clearNewFlag(watchID: Int64, itemID: String) {
        row(watchID: watchID, itemID: itemID)?.isNew = false
        save()
    }

    func clearAllNewFlags() {
        let descriptor = FetchDescriptor<ListingEntity>(predicate: #Predicate { $0.isNew })
        for row in (try? context.fetch(descriptor)) ?? [] { row.isNew = false }
        save()
    }

    /// "Effacer l'historique" — keeps the veilles and the favourites.
    func clearHistoryKeepingFavorites() {
        let descriptor = FetchDescriptor<ListingEntity>(predicate: #Predicate { !$0.isFavorite })
        for row in (try? context.fetch(descriptor)) ?? [] { context.delete(row) }
        for point in (try? context.fetch(FetchDescriptor<PricePointEntity>())) ?? [] {
            context.delete(point)
        }
        save()
    }

    /// Keeps storage bounded. Favourites are never dropped.
    func trim(watchID: Int64) {
        let rows = fetchEntities(watchID: watchID)
            .filter { !$0.isFavorite }
            .sorted { $0.firstSeenAt > $1.firstSeenAt }
        guard rows.count > retentionPerWatch else { return }
        for row in rows[retentionPerWatch...] { context.delete(row) }
        save()
    }

    // MARK: Internals

    private func fetchEntities(watchID: Int64) -> [ListingEntity] {
        let descriptor = FetchDescriptor<ListingEntity>(
            predicate: #Predicate { $0.watchID == watchID }
        )
        return (try? context.fetch(descriptor)) ?? []
    }

    private func row(watchID: Int64, itemID: String) -> ListingEntity? {
        let descriptor = FetchDescriptor<ListingEntity>(
            predicate: #Predicate { $0.watchID == watchID && $0.itemID == itemID }
        )
        return try? context.fetch(descriptor).first
    }

    /// A failed save must never take the app down; the next scan will try again.
    private func save() {
        do { try context.save() } catch { RDLog.error("store save failed: \(error)") }
    }
}
