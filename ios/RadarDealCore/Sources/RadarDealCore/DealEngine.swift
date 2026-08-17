import Foundation

/// The local "bonne affaire" detector.
///
/// RadarDeal makes no claim about a real market price. It only compares a listing to the
/// **median of the listings it has itself collected for that same watch**, and every label it
/// produces says exactly that ("32 % sous la médiane des annonces détectées").
///
/// The engine stays silent until it has seen `minComparables` priced listings for the watch: a
/// median over three items is noise, and a wrong "EXCELLENT DEAL" badge is worse than none.
public enum DealEngine {

    /// Below this many priced listings for a watch, no rating is emitted.
    public static let minComparables = 8

    /// A price this far under the median is an excellent deal.
    public static let excellentThreshold = 0.35

    /// A price this far under the median is a good price.
    public static let goodThreshold = 0.20

    /// Outliers distort a median far less than a mean, but a 1 € "lot de photos" listing still
    /// drags a small sample.
    private static let absurdLowRatio = 0.05

    public struct Assessment: Equatable, Sendable {
        public let rating: DealRating
        /// 0.32 for "32 % under the median". Nil when no median could be computed.
        public let discount: Double?
        public let median: Double?
        public let comparableCount: Int

        public static let none = Assessment(
            rating: .none, discount: nil, median: nil, comparableCount: 0
        )
    }

    /// Classic median: mean of the two middle values on an even-sized sample.
    public static func median(_ values: [Double]) -> Double? {
        let sorted = values.filter { $0.isFinite && $0 > 0 }.sorted()
        guard !sorted.isEmpty else { return nil }
        let middle = sorted.count / 2
        return sorted.count % 2 == 1
            ? sorted[middle]
            : (sorted[middle - 1] + sorted[middle]) / 2
    }

    /// Median after discarding absurdly low entries, which on Vinted are usually bundle
    /// placeholders rather than comparable items.
    public static func robustMedian(_ prices: [Double]) -> Double? {
        guard let raw = median(prices) else { return nil }
        let cleaned = prices.filter { $0.isFinite && $0 > raw * absurdLowRatio }
        return median(cleaned) ?? raw
    }

    /// Rates `price` against the other prices seen for the watch. `allPrices` is expected to
    /// include `price` itself; that is harmless for a median.
    public static func assess(price: Double?, allPrices: [Double]) -> Assessment {
        let usable = allPrices.filter { $0.isFinite && $0 > 0 }

        guard let price, price > 0, usable.count >= minComparables else {
            return Assessment(rating: .none, discount: nil, median: nil,
                              comparableCount: usable.count)
        }
        guard let median = robustMedian(usable), median > 0 else {
            return Assessment(rating: .none, discount: nil, median: nil,
                              comparableCount: usable.count)
        }

        let discount = (median - price) / median
        let rating: DealRating
        if discount >= excellentThreshold {
            rating = .excellent
        } else if discount >= goodThreshold {
            rating = .good
        } else {
            rating = .none
        }

        return Assessment(
            rating: rating,
            discount: discount > 0 ? discount : nil,
            median: median,
            comparableCount: usable.count
        )
    }
}
