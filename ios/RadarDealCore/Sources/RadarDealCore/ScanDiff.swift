import Foundation

/// A listing whose price went down between two scans.
public struct PriceDrop: Equatable, Sendable {
    public let listing: Listing
    public let oldPrice: Double
    public let newPrice: Double

    public var ratio: Double { oldPrice > 0 ? (oldPrice - newPrice) / oldPrice : 0 }
}

/// The comparison engine: what changed between what RadarDeal already knew for a watch and what
/// the latest scan returned.
///
/// A pure function on purpose — no database, no clock, no UIKit — so the "new listing" and
/// "price drop" rules are fully covered by unit tests on any machine.
///
/// Two rules matter most:
///
/// 1. **The first scan is a baseline.** When `baselineDone` is false every result is stored but
///    nothing is flagged as new, so creating a watch on a search with 52 results does not fire
///    52 notifications.
/// 2. **Missing data never erases known data.** If a scan returns a listing without its price
///    or its photo, the previously stored values are kept rather than overwritten with nil.
public enum ScanDiff {

    public struct Outcome: Equatable, Sendable {
        /// Rows to persist.
        public let listings: [Listing]
        /// Price observations to append.
        public let pricePoints: [(itemID: String, point: PricePoint)]
        /// Listings seen for the first time — empty during a baseline scan.
        public let newListings: [Listing]
        /// Listings whose price decreased since the previous scan.
        public let priceDrops: [PriceDrop]

        public var isEmpty: Bool { listings.isEmpty }

        public static func == (lhs: Outcome, rhs: Outcome) -> Bool {
            lhs.listings == rhs.listings
                && lhs.newListings == rhs.newListings
                && lhs.priceDrops == rhs.priceDrops
                && lhs.pricePoints.count == rhs.pricePoints.count
        }
    }

    public static func diff(
        watchID: Int64,
        known: [String: Listing],
        incoming: [RawListing],
        baselineDone: Bool,
        now: Date = Date()
    ) -> Outcome {
        var listings: [Listing] = []
        listings.reserveCapacity(incoming.count)
        var pricePoints: [(itemID: String, point: PricePoint)] = []
        var newListings: [Listing] = []
        var priceDrops: [PriceDrop] = []

        // A single response can repeat an id across pages; keep the first occurrence.
        var seen = Set<String>()
        seen.reserveCapacity(incoming.count)

        for raw in incoming {
            let trimmedID = raw.itemID.trimmingCharacters(in: .whitespaces)
            guard !trimmedID.isEmpty, seen.insert(raw.itemID).inserted else { continue }

            let existing = known[raw.itemID]
            let listing: Listing

            if let existing {
                let (merged, drop) = merge(existing: existing, raw: raw, now: now)
                if let drop { priceDrops.append(drop) }
                listing = merged
            } else {
                let fresh = buildFresh(watchID: watchID, raw: raw, now: now,
                                       flagAsNew: baselineDone)
                if baselineDone { newListings.append(fresh) }
                listing = fresh
            }

            listings.append(listing)

            if let price = listing.price, existing == nil || existing?.price != price {
                pricePoints.append(
                    (itemID: listing.itemID, point: PricePoint(price: price, recordedAt: now))
                )
            }
        }

        return Outcome(
            listings: listings,
            pricePoints: pricePoints,
            newListings: newListings,
            priceDrops: priceDrops
        )
    }

    // MARK: - Helpers

    private static func buildFresh(
        watchID: Int64,
        raw: RawListing,
        now: Date,
        flagAsNew: Bool
    ) -> Listing {
        Listing(
            itemID: raw.itemID,
            watchID: watchID,
            title: raw.title,
            brand: raw.brand,
            size: raw.size,
            condition: raw.condition,
            price: raw.price,
            currency: raw.currency,
            previousPrice: nil,
            imageURL: raw.imageURL,
            itemURL: raw.itemURL,
            firstSeenAt: now,
            lastSeenAt: now,
            isNew: flagAsNew,
            isFavorite: false,
            priceDroppedAt: nil
        )
    }

    /// Folds a fresh observation into a listing RadarDeal already knows, and reports the price
    /// drop **caused by this scan** — a drop detected earlier is still visible on the card via
    /// `previousPrice`, but it is never announced twice.
    ///
    /// User-owned state (`isFavorite`, `firstSeenAt`) and any field the scan did not return are
    /// preserved.
    private static func merge(
        existing: Listing,
        raw: RawListing,
        now: Date
    ) -> (Listing, PriceDrop?) {
        let incomingPrice = raw.price
        let knownPrice = existing.price

        let priceMoved: Bool
        if let incomingPrice, let knownPrice {
            priceMoved = incomingPrice != knownPrice
        } else {
            priceMoved = false
        }
        let dropped = priceMoved && (incomingPrice ?? 0) < (knownPrice ?? 0)

        var merged = existing
        merged.title = raw.title ?? existing.title
        merged.brand = raw.brand ?? existing.brand
        merged.size = raw.size ?? existing.size
        merged.condition = raw.condition ?? existing.condition
        merged.price = incomingPrice ?? knownPrice
        merged.currency = raw.currency ?? existing.currency
        merged.previousPrice = priceMoved ? knownPrice : existing.previousPrice
        merged.imageURL = raw.imageURL ?? existing.imageURL
        merged.itemURL = raw.itemURL ?? existing.itemURL
        merged.lastSeenAt = now
        merged.priceDroppedAt = dropped ? now : existing.priceDroppedAt

        let drop: PriceDrop? = dropped
            ? PriceDrop(listing: merged, oldPrice: knownPrice!, newPrice: incomingPrice!)
            : nil

        return (merged, drop)
    }
}
