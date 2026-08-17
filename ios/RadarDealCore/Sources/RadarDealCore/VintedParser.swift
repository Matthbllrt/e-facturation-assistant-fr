import Foundation

/// Turns a Vinted catalogue response into `RawListing`s.
///
/// The guiding rule, identical to Android's: **no input may ever throw**. Vinted changes its
/// payload shape without notice, and a shape RadarDeal does not recognise has to degrade into
/// "0 annonces analysées" — never into a crash. Every field is read through an optional
/// accessor, and the only hard requirement for keeping a listing is that it carries an id.
public enum VintedParser {

    private static let itemArrayKeys = ["items", "catalogItems", "catalog_items"]

    private static let listingMarkerKeys: Set<String> = [
        "title", "brand_title", "size_title", "price", "total_item_price", "photo", "url",
    ]

    /// Markers of an interstitial page asking the human to prove they are one.
    private static let challengeMarkers = [
        "captcha", "datadome", "px-captcha", "hcaptcha", "recaptcha",
        "just a moment", "attention required", "checking your browser",
        "cf-challenge", "/cdn-cgi/challenge-platform",
    ]

    // MARK: - Detection

    /// True when `body` is an anti-bot interstitial rather than a catalogue response.
    public static func looksLikeChallenge(_ body: String?) -> Bool {
        guard let body, !body.isEmpty else { return false }
        // A JSON catalogue response is never a challenge page, and can legitimately contain
        // the word "captcha" inside a listing title.
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{") && body.contains("\"items\"") { return false }
        let head = String(body.prefix(4096)).lowercased()
        return challengeMarkers.contains { head.contains($0) }
    }

    /// True when the payload is HTML rather than the JSON catalogue we asked for.
    public static func looksLikeHTML(_ body: String?) -> Bool {
        guard let body else { return false }
        let head = String(body.trimmingCharacters(in: .whitespacesAndNewlines).prefix(512))
            .lowercased()
        return head.hasPrefix("<!doctype html") || head.hasPrefix("<html") || head.hasPrefix("<?xml")
    }

    // MARK: - Parsing

    /// Parses `body`, which may be the catalogue JSON or an HTML page with the same JSON
    /// embedded in a `<script>` tag. Returns an empty array when nothing usable was found.
    public static func parse(_ body: String?, host: String = "www.vinted.fr") -> [RawListing] {
        guard let body, !body.isEmpty else { return [] }

        let direct = parseJSON(body, host: host)
        if !direct.isEmpty { return direct }

        return parseEmbeddedJSON(body, host: host)
    }

    private static func parseJSON(_ body: String, host: String) -> [RawListing] {
        guard
            let data = body.data(using: .utf8),
            let root = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else {
            return []
        }
        guard let array = findItemsArray(root, depth: 0) else { return [] }
        return array.compactMap { element in
            guard let object = element as? [String: Any] else { return nil }
            return readListing(object, host: host)
        }
    }

    /// Depth-limited search for the listings array, wherever the payload happens to nest it.
    private static func findItemsArray(_ element: Any, depth: Int) -> [Any]? {
        if depth > 6 { return nil }

        if let array = element as? [Any] {
            if array.contains(where: { ($0 as? [String: Any]).map(looksLikeItem) ?? false }) {
                return array
            }
            for child in array.prefix(20) {
                if let found = findItemsArray(child, depth: depth + 1) { return found }
            }
            return nil
        }

        if let object = element as? [String: Any] {
            for key in itemArrayKeys {
                if let candidate = object[key] as? [Any],
                   candidate.contains(where: { ($0 as? [String: Any]).map(looksLikeItem) ?? false }) {
                    return candidate
                }
            }
            for (_, child) in object {
                if let found = findItemsArray(child, depth: depth + 1) { return found }
            }
        }

        return nil
    }

    /// An object is a listing when it has an id and at least one listing-ish field.
    private static func looksLikeItem(_ object: [String: Any]) -> Bool {
        guard object["id"] != nil else { return false }
        return object.keys.contains { listingMarkerKeys.contains($0) }
    }

    private static func readListing(_ object: [String: Any], host: String) -> RawListing? {
        guard let id = stringOf(object["id"]), !id.isEmpty else { return nil }

        // Vinted has shipped the price as a bare number, as a string and as an object; and
        // "total_item_price" came later than "price".
        let priceElement = object["price"] ?? object["total_item_price"]
        let price = priceOf(priceElement)
        let currency = currencyOf(priceElement) ?? currencyOf(object["total_item_price"])

        return RawListing(
            itemID: id,
            title: stringOf(object["title"]),
            brand: stringOf(object["brand_title"])
                ?? stringOf((object["brand"] as? [String: Any])?["title"]),
            size: stringOf(object["size_title"])
                ?? stringOf((object["size"] as? [String: Any])?["title"]),
            condition: stringOf(object["status"]) ?? stringOf(object["condition"]),
            price: price,
            currency: currency ?? "EUR",
            imageURL: photoURL(from: object),
            itemURL: absolutize(stringOf(object["url"]) ?? stringOf(object["path"]),
                                host: host, id: id)
        )
    }

    // MARK: - Field readers

    private static func stringOf(_ value: Any?) -> String? {
        switch value {
        case let text as String:
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return (trimmed.isEmpty || trimmed == "null") ? nil : text
        case let number as NSNumber:
            // Booleans are never a usable field value here.
            if isBoolean(number) { return nil }
            if number.doubleValue == number.doubleValue.rounded() {
                return String(number.int64Value)
            }
            return number.stringValue
        default:
            return nil
        }
    }

    /// Distinguishes a JSON boolean from a JSON number.
    ///
    /// `CFGetTypeID` is Darwin-only, so this uses the encoded Objective-C type, which
    /// swift-corelibs-foundation implements identically: `NSNumber` wrapping a `Bool` reports
    /// "c". That keeps the parser compiling — and therefore testable — on Linux as well as iOS.
    private static func isBoolean(_ number: NSNumber) -> Bool {
        String(cString: number.objCType) == "c"
    }

    private static func priceOf(_ value: Any?) -> Double? {
        switch value {
        case let number as NSNumber:
            if isBoolean(number) { return nil }
            return sanitize(number.doubleValue)
        case let text as String:
            return lenientDouble(text)
        case let object as [String: Any]:
            if let amount = stringOf(object["amount"]) { return lenientDouble(amount) }
            if let value = stringOf(object["value"]) { return lenientDouble(value) }
            return nil
        default:
            return nil
        }
    }

    private static func currencyOf(_ value: Any?) -> String? {
        guard let object = value as? [String: Any] else { return nil }
        return stringOf(object["currency_code"]) ?? stringOf(object["currency"])
    }

    private static func lenientDouble(_ text: String) -> Double? {
        let cleaned = text
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: ",", with: ".")
            .filter { $0.isNumber || $0 == "." || $0 == "-" }
        guard let value = Double(cleaned) else { return nil }
        return sanitize(value)
    }

    private static func sanitize(_ value: Double) -> Double? {
        (value.isFinite && value >= 0) ? value : nil
    }

    private static func photoURL(from object: [String: Any]) -> String? {
        let photo = (object["photo"] as? [String: Any])
            ?? ((object["photos"] as? [Any])?.first as? [String: Any])

        guard let photo else { return stringOf(object["image_url"]) }

        if let url = stringOf(photo["url"]) { return url }
        if let url = stringOf(photo["full_size_url"]) { return url }

        let thumbnails = photo["thumbnails"] as? [Any]
        // Prefer a mid-size thumbnail: card images are large, but not full resolution.
        let preferred = (thumbnails?.last as? [String: Any]) ?? (thumbnails?.first as? [String: Any])
        return stringOf(preferred?["url"])
    }

    private static func absolutize(_ url: String?, host: String, id: String) -> String {
        guard let url, !url.isEmpty else { return "https://\(host)/items/\(id)" }
        if url.hasPrefix("http://") || url.hasPrefix("https://") { return url }
        if url.hasPrefix("/") { return "https://\(host)\(url)" }
        return "https://\(host)/\(url)"
    }

    // MARK: - HTML fallback

    /// Best-effort recovery when Vinted answers with the rendered search page instead of JSON.
    /// The page embeds the same catalogue payload inside a script tag; this walks the document
    /// looking for a balanced JSON object that contains an items array.
    static func parseEmbeddedJSON(_ body: String, host: String) -> [RawListing] {
        let characters = Array(body)
        let marker = Array("\"items\":[")

        var searchFrom = 0
        var attempts = 0

        while attempts < 5 {
            attempts += 1
            guard let markerAt = indexOf(marker, in: characters, from: searchFrom) else {
                return []
            }
            searchFrom = markerAt + marker.count

            guard let objectStart = lastIndex(of: "{", in: characters, before: markerAt) else {
                continue
            }
            guard let objectEnd = matchingBrace(in: characters, from: objectStart) else {
                continue
            }

            let candidate = String(characters[objectStart...objectEnd])
            let parsed = parseJSON(candidate, host: host)
            if !parsed.isEmpty { return parsed }
        }

        return []
    }

    private static func indexOf(_ needle: [Character], in haystack: [Character], from: Int) -> Int? {
        guard !needle.isEmpty, haystack.count >= needle.count else { return nil }
        var i = max(0, from)
        let limit = haystack.count - needle.count
        while i <= limit {
            if haystack[i] == needle[0] {
                var matched = true
                for j in 1..<needle.count where haystack[i + j] != needle[j] {
                    matched = false
                    break
                }
                if matched { return i }
            }
            i += 1
        }
        return nil
    }

    private static func lastIndex(of char: Character, in text: [Character], before: Int) -> Int? {
        var i = min(before, text.count - 1)
        while i >= 0 {
            if text[i] == char { return i }
            i -= 1
        }
        return nil
    }

    /// Index of the `}` closing the `{` at `start`, honouring strings and escapes.
    private static func matchingBrace(in text: [Character], from start: Int) -> Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        let limit = min(text.count, start + 4_000_000)

        while i < limit {
            let c = text[i]
            if escaped {
                escaped = false
            } else if c == "\\" && inString {
                escaped = true
            } else if c == "\"" {
                inString.toggle()
            } else if !inString {
                if c == "{" {
                    depth += 1
                } else if c == "}" {
                    depth -= 1
                    if depth == 0 { return i }
                }
            }
            i += 1
        }
        return nil
    }
}
