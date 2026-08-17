import Foundation

/// Translation between what the user asked for and the URLs RadarDeal actually requests.
///
/// Two shapes are supported, exactly as on Android:
///
/// * **URL-based watches** — the user pastes a Vinted search URL. Its query string already
///   encodes every advanced filter (catégorie, taille, état, couleur, marque…), so RadarDeal
///   forwards those parameters unchanged to the catalogue endpoint the Vinted web app itself
///   calls when you scroll that same page.
/// * **Criteria-based watches** — marque / mot-clé / prix max typed in the app.
public enum VintedQuery {

    /// Marketplaces a URL is accepted from: "vinted.fr", "www.vinted.co.uk", …
    private static let hostPattern = #"^(?:www\.)?vinted\.(?:[a-z]{2,3})(?:\.[a-z]{2,3})?$"#

    /// Vinted's own web front-end asks for this page size.
    private static let perPage = 48

    /// Parameters named differently on the search page and on the catalogue endpoint.
    private static let renames: [String: String] = [
        "catalog": "catalog_ids",
        "catalog_id": "catalog_ids",
        "brand_id": "brand_ids",
        "size_id": "size_ids",
        "status_id": "status_ids",
        "color_id": "color_ids",
        "material_id": "material_ids",
    ]

    /// Never forwarded: they belong to the HTML page or would pin the request to a stale page.
    private static let dropped: Set<String> = [
        "page", "per_page", "time", "disabled_personalization",
    ]

    // MARK: - URL validation

    public enum URLCheck: Equatable {
        case valid(host: String, normalized: String)
        case invalid(reason: String)
    }

    /// Validates a pasted URL and reports, in plain French, why it cannot be used.
    public static func checkSearchURL(_ raw: String?) -> URLCheck {
        let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .invalid(reason: "Collez une URL de recherche Vinted.")
        }

        let withScheme = trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://")
            ? trimmed
            : "https://" + trimmed

        guard
            let components = URLComponents(string: withScheme),
            let host = components.host?.lowercased(),
            !host.isEmpty
        else {
            return .invalid(reason: "Cette adresse n'est pas une URL valide.")
        }

        guard host.range(of: hostPattern, options: .regularExpression) != nil else {
            return .invalid(reason: "Cette URL ne vient pas de Vinted.")
        }

        if components.path.contains("/items/") {
            return .invalid(
                reason: "Ceci est l'adresse d'une annonce, pas d'une recherche. "
                    + "Lancez une recherche sur Vinted puis copiez l'adresse de la page de résultats."
            )
        }

        return .valid(host: host, normalized: withScheme)
    }

    // MARK: - Endpoints

    public struct Endpoints: Equatable {
        public let host: String
        public let apiURL: String
        public let browseURL: String
    }

    /// Builds the endpoints for `watch`. `fallbackHost` is the marketplace configured in
    /// Réglages, used for criteria-based watches which carry no host of their own.
    public static func endpoints(
        for watch: Watch,
        fallbackHost: String = "www.vinted.fr"
    ) -> Endpoints {
        if let source = watch.sourceURL,
           case let .valid(host, normalized) = checkSearchURL(source) {
            let params = forwardedParams(from: normalized)
            return Endpoints(
                host: host,
                apiURL: buildAPIURL(host: host, params: params),
                browseURL: source
            )
        }

        let host = fallbackHost.isEmpty ? "www.vinted.fr" : fallbackHost
        let params = criteriaParams(for: watch)
        return Endpoints(
            host: host,
            apiURL: buildAPIURL(host: host, params: params),
            browseURL: buildBrowseURL(host: host, params: params)
        )
    }

    // MARK: - Parameters

    /// Reads the query string of a search page and returns the parameters to forward, with
    /// `foo[]=1&foo[]=2` collapsed into `foo=1,2` the way the catalogue endpoint expects.
    public static func forwardedParams(from url: String) -> [(String, String)] {
        var grouped: [(key: String, values: [String])] = []

        // The raw query string is split by hand rather than through URLComponents.
        //
        // Foundation is not consistent here: on Darwin `queryItems` percent-decodes values, in
        // swift-corelibs-foundation it does not, and `percentEncodedQueryItems` double-encodes
        // them on Linux ("air%20max" comes back as "air%2520max"). Splitting the string
        // ourselves gives one behaviour on every platform, and the same one the Android
        // implementation has.
        let rawQuery = url.firstIndex(of: "?").map { String(url[url.index(after: $0)...]) } ?? ""

        for pair in rawQuery.split(separator: "&", omittingEmptySubsequences: true) {
            guard let separator = pair.firstIndex(of: "="), separator != pair.startIndex else {
                continue
            }
            let rawKey = String(pair[pair.startIndex..<separator])
            let rawValue = String(pair[pair.index(after: separator)...])

            let value = decode(rawValue)
            guard !value.isEmpty else { continue }

            var key = decode(rawKey)
            if key.hasSuffix("[]") { key = String(key.dropLast(2)) }
            guard !key.isEmpty, !dropped.contains(key) else { continue }
            let mapped = renames[key] ?? key

            if let index = grouped.firstIndex(where: { $0.key == mapped }) {
                grouped[index].values.append(value)
            } else {
                grouped.append((key: mapped, values: [value]))
            }
        }

        var result = grouped.map { ($0.key, $0.values.joined(separator: ",")) }
        // Newest first is the only ordering that makes a radar meaningful.
        result.removeAll { $0.0 == "order" }
        result.append(("order", "newest_first"))
        return result
    }

    public static func criteriaParams(for watch: Watch) -> [(String, String)] {
        var params: [(String, String)] = []

        let text = [watch.brand, watch.keyword]
            .compactMap { $0?.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        if !text.isEmpty { params.append(("search_text", text)) }

        if let min = watch.minPrice { params.append(("price_from", trimNumber(min))) }
        if let max = watch.maxPrice { params.append(("price_to", trimNumber(max))) }
        if watch.minPrice != nil || watch.maxPrice != nil {
            params.append(("currency", "EUR"))
        }
        params.append(("order", "newest_first"))
        return params
    }

    // MARK: - Building

    private static func buildAPIURL(host: String, params: [(String, String)]) -> String {
        var query = "page=1&per_page=\(perPage)"
        for (key, value) in params {
            query += "&\(encode(key))=\(encode(value))"
        }
        return "https://\(host)/api/v2/catalog/items?\(query)"
    }

    private static func buildBrowseURL(host: String, params: [(String, String)]) -> String {
        let query = params.map { "\(encode($0.0))=\(encode($0.1))" }.joined(separator: "&")
        return query.isEmpty ? "https://\(host)/catalog" : "https://\(host)/catalog?\(query)"
    }

    /// Reverses query-string encoding: "+" is a space, then percent escapes are resolved.
    private static func decode(_ value: String) -> String {
        let withSpaces = value.replacingOccurrences(of: "+", with: " ")
        return withSpaces.removingPercentEncoding ?? withSpaces
    }

    private static func trimNumber(_ value: Double) -> String {
        value == value.rounded() && abs(value) < 1e15
            ? String(Int64(value))
            : String(value)
    }

    /// Percent-encoding that matches what the Android side produces, including `+` for spaces.
    private static func encode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-_.*")
        let escaped = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
        return escaped.replacingOccurrences(of: "%20", with: "+")
    }
}
