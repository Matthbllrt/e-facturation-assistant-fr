import Foundation

/// Small formatting helpers shared by every screen.
///
/// They are deliberately optional-tolerant: a Vinted listing can be missing its price, its size
/// or its brand, and no screen may ever crash or show "nil" because of it.
public enum Formatters {

    /// "42 €", "39,50 €", or `fallback` when the price is unknown.
    public static func price(
        _ amount: Double?,
        currency: String? = "EUR",
        fallback: String = "Prix inconnu"
    ) -> String {
        guard let amount, amount.isFinite else { return fallback }

        let symbol = currencySymbol(currency)
        let rounded = (amount * 100).rounded() / 100

        if abs(rounded - rounded.rounded()) < 0.005 {
            return "\(Int(rounded.rounded())) \(symbol)"
        }
        // French convention: comma as the decimal separator, independent of device locale so
        // the app reads the same everywhere.
        let text = String(format: "%.2f", rounded).replacingOccurrences(of: ".", with: ",")
        return "\(text) \(symbol)"
    }

    public static func currencySymbol(_ currency: String?) -> String {
        switch currency?.uppercased() {
        case nil, "", "EUR": return "€"
        case "GBP": return "£"
        case "USD": return "$"
        case "PLN": return "zł"
        case "CZK": return "Kč"
        case "SEK": return "kr"
        case "HUF": return "Ft"
        case let other?: return other
        }
    }

    /// "Il y a 18 sec", "Il y a 4 min", "Il y a 3 h", "Il y a 2 j".
    public static func relativeTime(_ date: Date?, now: Date = Date()) -> String {
        guard let date, date.timeIntervalSince1970 > 0 else { return "Jamais" }

        let delta = now.timeIntervalSince(date)
        if delta < 0 { return "À l'instant" }

        let seconds = Int(delta)
        switch seconds {
        case ..<10: return "À l'instant"
        case ..<60: return "Il y a \(seconds) sec"
        case ..<3600: return "Il y a \(seconds / 60) min"
        case ..<86_400: return "Il y a \(seconds / 3600) h"
        case ..<2_592_000: return "Il y a \(seconds / 86_400) j"
        default: return "Il y a \(seconds / 2_592_000) mois"
        }
    }

    /// Compact interval used on the watch list: "30 sec", "2 min", "1 h".
    public static func duration(seconds: Int) -> String {
        if seconds < 60 { return "\(seconds) sec" }
        if seconds % 3600 == 0 { return "\(seconds / 3600) h" }
        return "\(seconds / 60) min"
    }

    /// "32 %" for a 0.324 ratio.
    public static func percent(_ ratio: Double?) -> String {
        guard let ratio, ratio.isFinite else { return "—" }
        return "\(Int((ratio * 100).rounded())) %"
    }
}
