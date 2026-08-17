import Foundation
import WebKit
import RadarDealCore

/// Logging façade. Verbose output is compiled in for DEBUG only, and never carries a cookie,
/// a token, a URL with credentials or any other personal data — counts and statuses only.
enum RDLog {
    static func debug(_ message: @autoclosure () -> String) {
        #if DEBUG
        print("[RadarDeal] \(message())")
        #endif
    }

    static func error(_ message: @autoclosure () -> String) {
        print("[RadarDeal][error] \(message())")
    }
}

/// Result of one attempt to read a Vinted catalogue page.
///
/// Every failure mode is a named case rather than a thrown error, so the scheduler and the UI
/// can react to each one specifically instead of showing something generic.
enum FetchOutcome {
    case success(String)
    /// No usable session — the user has to sign in to Vinted inside RadarDeal.
    case needsLogin
    /// Vinted served an anti-bot interstitial; only the user can clear it.
    case needsVerification
    case rateLimited
    case serverError(Int)
    case networkError(String?)

    var asStatus: ScanStatus {
        switch self {
        case .success: return .ok
        case .needsLogin: return .needsLogin
        case .needsVerification: return .needsVerification
        case .rateLimited: return .rateLimited
        case .serverError, .networkError: return .networkError
        }
    }
}

/// Read-only view of the Vinted session that lives in WebKit's own cookie store.
///
/// RadarDeal never asks for, stores or transmits Vinted credentials. The user signs in on
/// Vinted's real login page inside a `WKWebView`; WebKit keeps the resulting cookies in the
/// app's own data store, and this class only ever *reads* whether such cookies exist so the app
/// can say "connexion nécessaire" instead of failing silently.
@MainActor
final class VintedSession {

    /// The single non-persistent-free data store shared by the login screen and the fetcher, so
    /// a sign-in is immediately visible to the engine.
    let dataStore = WKWebsiteDataStore.default()

    private var cachedUserAgent: String?

    /// Cookie names that indicate Vinted considers this client a real, signed-in browser.
    private let sessionCookieHints = ["_vinted_fr_session", "access_token_web", "v_udt", "anon_id"]

    func cookies(for host: String) async -> [HTTPCookie] {
        let all = await dataStore.httpCookieStore.allCookies()
        let bare = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        return all.filter { $0.domain.contains(bare) }
    }

    func hasSession(host: String) async -> Bool {
        let names = Set(await cookies(for: host).map(\.name))
        return sessionCookieHints.contains { names.contains($0) }
    }

    /// The WebView's own user agent, so HTTP requests match the cookies they carry.
    func userAgent() async -> String {
        if let cachedUserAgent { return cachedUserAgent }
        let webView = WKWebView(frame: .zero)
        let value = try? await webView.evaluateJavaScript("navigator.userAgent") as? String
        let resolved = (value as? String) ?? Self.fallbackUserAgent
        cachedUserAgent = resolved
        return resolved
    }

    func signOut() async {
        let store = dataStore.httpCookieStore
        for cookie in await store.allCookies() where cookie.domain.contains("vinted") {
            await store.deleteCookie(cookie)
        }
    }

    private static let fallbackUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
}

/// Reads a Vinted catalogue page over HTTP, carrying the cookies the user's own WebKit session
/// already holds.
///
/// This is the same request the Vinted web app makes when you scroll a search page. No
/// protection is worked around, and when Vinted declines the outcome is reported honestly so
/// the user can act on it.
actor VintedFetcher {

    private let session: VintedSession
    private let urlSession: URLSession

    init(session: VintedSession) {
        self.session = session
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        urlSession = URLSession(configuration: configuration)
    }

    func fetch(host: String, apiURL: String, referer: String) async -> FetchOutcome {
        guard let url = URL(string: apiURL) else { return .networkError("bad-url") }

        let cookies = await session.cookies(for: host)
        let userAgent = await session.userAgent()

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        request.setValue("fr-FR,fr;q=0.9,en;q=0.8", forHTTPHeaderField: "Accept-Language")
        request.setValue(referer, forHTTPHeaderField: "Referer")
        request.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")

        if !cookies.isEmpty {
            let header = HTTPCookie.requestHeaderFields(with: cookies)
            for (key, value) in header { request.setValue(value, forHTTPHeaderField: key) }
        }

        do {
            let (data, response) = try await urlSession.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let body = String(data: data, encoding: .utf8)
            RDLog.debug("HTTP \(code), \(body?.count ?? 0) chars")
            return interpret(code: code, body: body, hasCookies: !cookies.isEmpty)
        } catch {
            let nsError = error as NSError
            if nsError.code == NSURLErrorNotConnectedToInternet {
                return .networkError("offline")
            }
            RDLog.debug("network failure: \(nsError.code)")
            return .networkError("\(nsError.code)")
        }
    }

    private func interpret(code: Int, body: String?, hasCookies: Bool) -> FetchOutcome {
        switch code {
        case 429: return .rateLimited
        case 401: return .needsLogin
        case 403: return hasCookies ? .needsVerification : .needsLogin
        case 500...599: return .serverError(code)
        case 200...299: break
        default: return .serverError(code)
        }

        guard let body, !body.isEmpty else { return .serverError(code) }
        if VintedParser.looksLikeChallenge(body) { return .needsVerification }
        // A 200 that hands back the HTML shell instead of JSON usually means the request was
        // not recognised as a signed-in API call.
        if VintedParser.looksLikeHTML(body) && !body.contains("\"items\"") {
            return hasCookies ? .needsVerification : .needsLogin
        }
        return .success(body)
    }
}
