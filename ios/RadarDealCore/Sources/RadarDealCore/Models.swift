import Foundation

// MARK: - Watch

/// A "veille" — one saved Vinted search that RadarDeal polls.
///
/// Mirrors the Android `Watch` so that both platforms describe the same product. A watch is
/// defined either by simple criteria (`keyword` / `brand` / `maxPrice`) or by a raw Vinted
/// search `sourceURL` pasted by the user; the URL form is the richer one because it preserves
/// every advanced filter Vinted encoded.
public struct Watch: Identifiable, Equatable, Sendable {
    public var id: Int64
    public var name: String
    public var keyword: String?
    public var brand: String?
    public var minPrice: Double?
    public var maxPrice: Double?
    public var sourceURL: String?
    public var intervalSeconds: Int
    /// Single-slot fast mode. At most one watch may hold it — see `ScanFrequency.ultra`.
    public var isUltra: Bool
    public var isActive: Bool
    public var notifyEnabled: Bool
    public var createdAt: Date
    public var lastScanAt: Date?
    public var lastScanStatus: ScanStatus
    /// False until the first successful scan has stored its results. The baseline scan records
    /// what already exists without flagging any of it as new.
    public var baselineDone: Bool

    public init(
        id: Int64 = 0,
        name: String,
        keyword: String? = nil,
        brand: String? = nil,
        minPrice: Double? = nil,
        maxPrice: Double? = nil,
        sourceURL: String? = nil,
        intervalSeconds: Int = ScanFrequency.standard.seconds,
        isUltra: Bool = false,
        isActive: Bool = true,
        notifyEnabled: Bool = true,
        createdAt: Date = Date(),
        lastScanAt: Date? = nil,
        lastScanStatus: ScanStatus = .never,
        baselineDone: Bool = false
    ) {
        self.id = id
        self.name = name
        self.keyword = keyword
        self.brand = brand
        self.minPrice = minPrice
        self.maxPrice = maxPrice
        self.sourceURL = sourceURL
        self.intervalSeconds = intervalSeconds
        self.isUltra = isUltra
        self.isActive = isActive
        self.notifyEnabled = notifyEnabled
        self.createdAt = createdAt
        self.lastScanAt = lastScanAt
        self.lastScanStatus = lastScanStatus
        self.baselineDone = baselineDone
    }

    public var isURLBased: Bool {
        guard let url = sourceURL else { return false }
        return !url.trimmingCharacters(in: .whitespaces).isEmpty
    }

    public var frequency: ScanFrequency {
        isUltra ? .ultra : ScanFrequency.from(seconds: intervalSeconds)
    }

    /// Polling floor in seconds, before adaptive back-off is applied.
    public var baseInterval: TimeInterval {
        isUltra ? ScanFrequency.ultra.interval : TimeInterval(intervalSeconds)
    }

    /// Short human summary shown under the watch name, e.g. "Nike · Air Max 95 · ≤ 80 €".
    public func criteriaSummary() -> String {
        var parts: [String] = []
        if isURLBased {
            parts.append("Recherche Vinted")
        } else {
            if let brand, !brand.isEmpty { parts.append(brand) }
            if let keyword, !keyword.isEmpty { parts.append(keyword) }
        }
        if let maxPrice { parts.append("≤ " + Formatters.price(maxPrice)) }
        return parts.isEmpty ? "Tous les résultats" : parts.joined(separator: " · ")
    }
}

// MARK: - Frequency

/// Preset polling intervals.
///
/// These are floors on how often RadarDeal *asks*, not promises about how fast Vinted
/// publishes — and on iOS they only apply while the app is in the foreground. See
/// `ios/README.md` for what the platform actually permits in the background.
public enum ScanFrequency: Int, CaseIterable, Sendable {
    case ultra = 3
    case fast = 5
    case standard = 15
    case eco = 30

    public var seconds: Int { rawValue }
    public var interval: TimeInterval { TimeInterval(rawValue) }

    public var label: String {
        switch self {
        case .ultra: return "Ultra"
        case .fast: return "Rapide"
        case .standard: return "Standard"
        case .eco: return "Éco"
        }
    }

    public var badge: String {
        switch self {
        case .ultra: return "⚡"
        case .fast: return "🔥"
        case .standard: return "●"
        case .eco: return "🌙"
        }
    }

    public var descriptionText: String {
        switch self {
        case .ultra: return "2–3 sec"
        case .fast: return "5 sec"
        case .standard: return "15 sec"
        case .eco: return "30 sec"
        }
    }

    /// Shown under the label so the battery cost is never a surprise.
    public var costHint: String {
        switch self {
        case .ultra: return "Vitesse maximale · consommation élevée"
        case .fast: return "Très réactif"
        case .standard: return "Bon équilibre"
        case .eco: return "Économe en batterie"
        }
    }

    /// Ultra is granted through its own path, never inferred from an interval.
    public static func from(seconds: Int) -> ScanFrequency {
        allCases
            .filter { $0 != .ultra }
            .min { abs($0.seconds - seconds) < abs($1.seconds - seconds) } ?? .standard
    }
}

// MARK: - Scan status

/// Outcome of the last scan. Every value maps to an explicit, non-technical state shown in the
/// UI — the app never silently swallows a failure.
public enum ScanStatus: String, Sendable {
    case never = "NEVER"
    case ok = "OK"
    case empty = "EMPTY"
    case needsLogin = "NEEDS_LOGIN"
    case needsVerification = "NEEDS_VERIFICATION"
    case rateLimited = "RATE_LIMITED"
    case networkError = "NETWORK_ERROR"
    case parseError = "PARSE_ERROR"
    /// Monitoring stopped itself because Vinted asked for a human check. RadarDeal makes no
    /// further request for this watch until the user has cleared it.
    case pausedVerification = "PAUSED_VERIFICATION"

    public var isProblem: Bool {
        switch self {
        case .needsLogin, .needsVerification, .pausedVerification,
             .rateLimited, .networkError, .parseError:
            return true
        default:
            return false
        }
    }

    /// True when RadarDeal must stop requesting until the user acts.
    public var haltsScanning: Bool { self == .pausedVerification }

    public var label: String {
        switch self {
        case .never: return "Jamais scanné"
        case .ok: return "À jour"
        case .empty: return "Aucun résultat"
        case .needsLogin: return "Connexion Vinted nécessaire"
        case .needsVerification: return "Vérification Vinted nécessaire"
        case .rateLimited: return "Trop de requêtes — pause"
        case .networkError: return "Erreur réseau"
        case .parseError: return "Réponse illisible"
        case .pausedVerification: return "Surveillance en pause — vérification Vinted"
        }
    }

    public var explanation: String {
        switch self {
        case .never: return "Cette veille n'a pas encore été scannée."
        case .ok: return "Dernier scan réussi."
        case .empty: return "Vinted n'a retourné aucune annonce pour ces critères."
        case .needsLogin:
            return "Ouvrez Réglages ▸ Connexion Vinted et connectez-vous à votre compte, "
                + "puis relancez la surveillance."
        case .needsVerification:
            return "Une vérification Vinted est nécessaire.\n\n"
                + "Ouvrez Vinted, terminez la vérification puis relancez la surveillance."
        case .rateLimited:
            return "Vinted a limité le nombre de requêtes. RadarDeal ralentit automatiquement "
                + "puis réessaiera. Choisissez une fréquence plus lente si cela se répète."
        case .networkError:
            return "Impossible de joindre Vinted. Vérifiez votre connexion internet."
        case .parseError:
            return "La réponse de Vinted n'a pas pu être analysée. RadarDeal réessaiera au "
                + "prochain scan."
        case .pausedVerification:
            return "Une vérification Vinted est nécessaire.\n\n"
                + "RadarDeal a arrêté d'interroger Vinted pour cette veille. Ouvrez Vinted, "
                + "terminez la vérification, puis relancez la surveillance."
        }
    }
}

// MARK: - Listing

/// One Vinted listing as RadarDeal knows it.
///
/// Everything except the identifiers and timestamps is optional on purpose: Vinted's payload
/// shape changes over time and a listing missing its size, brand or even its price must still
/// be storable and displayable.
public struct Listing: Identifiable, Equatable, Sendable {
    public var itemID: String
    public var watchID: Int64
    public var title: String?
    public var brand: String?
    public var size: String?
    public var condition: String?
    public var price: Double?
    public var currency: String?
    public var previousPrice: Double?
    public var imageURL: String?
    public var itemURL: String?
    public var firstSeenAt: Date
    public var lastSeenAt: Date
    public var isNew: Bool
    public var isFavorite: Bool
    public var priceDroppedAt: Date?

    public var id: String { "\(watchID):\(itemID)" }

    public init(
        itemID: String,
        watchID: Int64,
        title: String? = nil,
        brand: String? = nil,
        size: String? = nil,
        condition: String? = nil,
        price: Double? = nil,
        currency: String? = "EUR",
        previousPrice: Double? = nil,
        imageURL: String? = nil,
        itemURL: String? = nil,
        firstSeenAt: Date = Date(),
        lastSeenAt: Date = Date(),
        isNew: Bool = false,
        isFavorite: Bool = false,
        priceDroppedAt: Date? = nil
    ) {
        self.itemID = itemID
        self.watchID = watchID
        self.title = title
        self.brand = brand
        self.size = size
        self.condition = condition
        self.price = price
        self.currency = currency
        self.previousPrice = previousPrice
        self.imageURL = imageURL
        self.itemURL = itemURL
        self.firstSeenAt = firstSeenAt
        self.lastSeenAt = lastSeenAt
        self.isNew = isNew
        self.isFavorite = isFavorite
        self.priceDroppedAt = priceDroppedAt
    }

    /// True when the last scan saw a strictly lower price than the one before.
    public var hasPriceDrop: Bool {
        guard let previousPrice, let price else { return false }
        return price < previousPrice
    }

    /// 0.21 for 70 € → 55 €. Nil when there is nothing to compare.
    public var priceDropRatio: Double? {
        guard let before = previousPrice, let now = price, before > 0, now < before else {
            return nil
        }
        return (before - now) / before
    }

    public var displayTitle: String {
        guard let title, !title.isEmpty else { return "Annonce Vinted" }
        return title
    }

    /// "Nike · 42 · Très bon état" — skips whatever Vinted did not provide.
    public func subtitle() -> String {
        [brand, size, condition]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }
}

/// A stored price observation, used to draw the price history of a listing.
public struct PricePoint: Equatable, Sendable {
    public var price: Double
    public var recordedAt: Date

    public init(price: Double, recordedAt: Date) {
        self.price = price
        self.recordedAt = recordedAt
    }
}

// MARK: - Deals

/// How good a price looks compared to the other listings of the same watch.
public enum DealRating: Sendable {
    case none, good, excellent

    public var label: String {
        switch self {
        case .none: return ""
        case .good: return "BON PRIX"
        case .excellent: return "🔥 EXCELLENT DEAL"
        }
    }
}

/// A listing enriched with everything a card needs to render.
///
/// `medianDiscount` is expressed relative to the median of the listings RadarDeal has actually
/// seen for this watch — never presented as a market price.
public struct ListingCard: Identifiable, Equatable, Sendable {
    public var listing: Listing
    public var watchName: String
    public var rating: DealRating
    public var medianDiscount: Double?
    public var comparableCount: Int

    public var id: String { listing.id }

    public init(
        listing: Listing,
        watchName: String,
        rating: DealRating = .none,
        medianDiscount: Double? = nil,
        comparableCount: Int = 0
    ) {
        self.listing = listing
        self.watchName = watchName
        self.rating = rating
        self.medianDiscount = medianDiscount
        self.comparableCount = comparableCount
    }

    /// "32 % sous la médiane", or nil when there is no basis to say it.
    public func discountLabel() -> String? {
        guard let d = medianDiscount, d > 0 else { return nil }
        return "\(Int(d * 100)) % sous la médiane"
    }
}

/// A listing exactly as it came off the wire, before RadarDeal diffs it against what it knows.
public struct RawListing: Equatable, Sendable {
    public var itemID: String
    public var title: String?
    public var brand: String?
    public var size: String?
    public var condition: String?
    public var price: Double?
    public var currency: String?
    public var imageURL: String?
    public var itemURL: String?

    public init(
        itemID: String,
        title: String? = nil,
        brand: String? = nil,
        size: String? = nil,
        condition: String? = nil,
        price: Double? = nil,
        currency: String? = "EUR",
        imageURL: String? = nil,
        itemURL: String? = nil
    ) {
        self.itemID = itemID
        self.title = title
        self.brand = brand
        self.size = size
        self.condition = condition
        self.price = price
        self.currency = currency
        self.imageURL = imageURL
        self.itemURL = itemURL
    }

    /// Minimal projection used on the hot path — no database row, no statistics.
    public func toListing(watchID: Int64, now: Date = Date()) -> Listing {
        Listing(
            itemID: itemID,
            watchID: watchID,
            title: title,
            brand: brand,
            size: size,
            condition: condition,
            price: price,
            currency: currency,
            imageURL: imageURL,
            itemURL: itemURL,
            firstSeenAt: now,
            lastSeenAt: now,
            isNew: true
        )
    }
}
