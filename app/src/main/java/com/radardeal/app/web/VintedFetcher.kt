package com.radardeal.app.web

import com.radardeal.app.core.RdLog

/**
 * The single entry point the monitoring layer uses to read Vinted.
 *
 * Strategy: try the cheap HTTP request first; if Vinted refuses it in a way a real browser
 * might not have been refused (login, verification, unexpected server answer), retry once
 * through the WebView, where the request originates from the Vinted page itself. If both agree
 * the session is unusable, the outcome is surfaced to the user unchanged — RadarDeal does not
 * loop, does not rotate anything and does not try to look like a different client.
 */
class VintedFetcher(
    private val http: HttpVintedFetcher,
    private val webView: WebViewVintedFetcher,
) {

    suspend fun fetch(host: String, apiUrl: String, referer: String): FetchOutcome {
        val first = http.fetch(host, apiUrl, referer)
        if (first is FetchOutcome.Success) return first

        if (!shouldEscalate(first) || !webView.isUsable) {
            return first
        }

        RdLog.d("Fetch", "HTTP path returned ${first::class.simpleName}, retrying via WebView")
        val second = webView.fetch(host, apiUrl, referer)

        // A WebView-specific infrastructure failure should not mask the more meaningful
        // verdict the HTTP path already produced.
        return if (second is FetchOutcome.NetworkError && first !is FetchOutcome.NetworkError) {
            first
        } else {
            second
        }
    }

    /** Rate limiting and a genuinely offline device are not worth a second, costlier attempt. */
    private fun shouldEscalate(outcome: FetchOutcome): Boolean = when (outcome) {
        is FetchOutcome.Success -> false
        FetchOutcome.RateLimited -> false
        is FetchOutcome.NetworkError -> outcome.reason != "offline"
        FetchOutcome.NeedsLogin, FetchOutcome.NeedsVerification -> true
        is FetchOutcome.ServerError -> true
    }

    fun release() = webView.release()
}
