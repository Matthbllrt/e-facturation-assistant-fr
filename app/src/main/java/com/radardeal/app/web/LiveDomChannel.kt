package com.radardeal.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.radardeal.app.core.RdLog
import com.radardeal.app.monitoring.RadarPerf
import com.radardeal.app.monitoring.RawListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the page-side observer reported. */
sealed interface LiveDomEvent {
    /** The observer installed itself and recorded [baselineCount] cards already on screen. */
    data class Ready(val baselineCount: Int) : LiveDomEvent

    /** A card that was not on the page when the observer started. */
    data class NewItem(val listing: RawListing, val observedAtMs: Long) : LiveDomEvent

    /** The page is showing a human-verification wall. */
    data object Challenge : LiveDomEvent
}

/**
 * Live detection channel: a persistent WebView parked on the watch's results page with a
 * `MutationObserver` installed, reporting cards as the page injects them.
 *
 * **What this is honestly worth.** Vinted's results page is not a live feed — it does not
 * stream new listings into an idle tab — so in steady state this channel is quiet and the
 * polling engine remains what actually finds listings. It earns its place in two situations:
 * when the page does re-render its grid (infinite scroll, the SPA refreshing a route), the
 * cards are reported the moment they enter the DOM rather than on the next poll; and it gives
 * the Ultra watch a second, independent detector so a missed poll is not a missed listing.
 * PERFORMANCE.md reports what it measured rather than what it promises.
 *
 * Everything here is optional. If the device's WebView is too old for
 * [WebViewFeature.WEB_MESSAGE_LISTENER], the channel disables itself and monitoring continues
 * on the polling engine alone.
 */
class LiveDomChannel(private val context: Context) {

    private var webView: WebView? = null
    private var currentWatchId: Long? = null
    private var currentUrl: String? = null
    private var listener: ((LiveDomEvent) -> Unit)? = null

    @Volatile
    private var unavailable = false

    /** True when this device can run the channel at all. */
    val isSupported: Boolean
        get() = !unavailable &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    val isAttached: Boolean get() = webView != null

    val attachedWatchId: Long? get() = currentWatchId

    /**
     * Parks the channel on [browseUrl] for [watchId]. Calling it again with the same watch and
     * URL is a no-op — the WebView is never torn down and rebuilt for a refresh.
     */
    suspend fun attach(
        watchId: Long,
        host: String,
        browseUrl: String,
        onEvent: (LiveDomEvent) -> Unit,
    ): Boolean = withContext(Dispatchers.Main) {
        if (!isSupported) {
            RdLog.d("LiveDom", "WebView too old for the live channel; polling only")
            return@withContext false
        }
        if (currentWatchId == watchId && currentUrl == browseUrl && webView != null) {
            return@withContext true
        }

        listener = onEvent
        try {
            val view = webView ?: createWebView(host) ?: return@withContext false
            currentWatchId = watchId
            currentUrl = browseUrl
            view.loadUrl(browseUrl)
            RadarPerf.recordWebViewActive(true)
            RdLog.i("LiveDom", "live channel attached to watch $watchId")
            true
        } catch (t: Throwable) {
            RdLog.e("LiveDom", "attach failed", t)
            unavailable = true
            RadarPerf.recordWebViewActive(false)
            false
        }
    }

    /**
     * Asks the parked page to re-render its results without a full navigation.
     *
     * This is deliberately *not* `reload()`: reloading throws away the JavaScript context, the
     * observer and its baseline, and costs a full page load. Instead the page is asked to
     * re-run its own search request, which keeps the observer alive to see the result.
     */
    suspend fun softRefresh() = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext
        runCatching {
            view.evaluateJavascript(SOFT_REFRESH_JS, null)
        }.onFailure { RdLog.w("LiveDom", "soft refresh failed") }
    }

    suspend fun detach() = withContext(Dispatchers.Main) {
        runCatching {
            webView?.let { view ->
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            }
        }
        webView = null
        currentWatchId = null
        currentUrl = null
        listener = null
        RadarPerf.recordWebViewActive(false)
        RdLog.i("LiveDom", "live channel detached")
    }

    // --- Internals ---------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun createWebView(host: String): WebView? = try {
        val view = WebView(context.applicationContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The grid's images are never displayed to anyone; skipping them saves the bulk of
            // the page's bandwidth and decode time.
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        val allowedOrigins = setOf("https://$host", "https://${host.removePrefix("www.")}")

        // The modern, origin-scoped bridge: unlike addJavascriptInterface it exposes no Java
        // object to the page and only accepts messages from the origins listed here.
        WebViewCompat.addWebMessageListener(
            view,
            LiveDomObserverScript.BRIDGE,
            allowedOrigins,
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            if (allowedOrigins.none { sourceOrigin.toString().startsWith(it) }) return@addWebMessageListener
            handleMessage(message.data)
        }

        WebViewCompat.addDocumentStartJavaScript(
            view,
            LiveDomObserverScript.SOURCE,
            allowedOrigins,
        )

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                v: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // Confine the channel to the marketplace it was opened for.
                val target = request?.url?.host ?: return false
                return !target.endsWith(host.removePrefix("www."))
            }
        }

        webView = view
        view
    } catch (t: Throwable) {
        RdLog.e("LiveDom", "WebView unavailable for the live channel", t)
        unavailable = true
        null
    }

    /** Parses one structured message. Anything malformed is dropped, never thrown. */
    private fun handleMessage(raw: String?) {
        val payload = raw ?: return
        val sink = listener ?: return
        runCatching {
            val json = JSONObject(payload)
            when (json.optString("type")) {
                LiveDomObserverScript.TYPE_READY ->
                    sink(LiveDomEvent.Ready(json.optInt("count")))

                LiveDomObserverScript.TYPE_CHALLENGE ->
                    sink(LiveDomEvent.Challenge)

                LiveDomObserverScript.TYPE_MUTATION ->
                    RadarPerf.recordDomMutation(json.optInt("count", 1))

                LiveDomObserverScript.TYPE_NEW_ITEM -> {
                    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@runCatching
                    sink(
                        LiveDomEvent.NewItem(
                            listing = RawListing(
                                itemId = id,
                                title = json.optStringOrNull("title"),
                                brand = null,
                                size = null,
                                condition = null,
                                price = json.optStringOrNull("priceText")?.let(::parsePrice),
                                currency = "EUR",
                                imageUrl = json.optStringOrNull("imageUrl"),
                                itemUrl = json.optStringOrNull("itemUrl"),
                            ),
                            observedAtMs = android.os.SystemClock.elapsedRealtime(),
                        ),
                    )
                }

                else -> Unit
            }
        }.onFailure { RdLog.w("LiveDom", "unparseable message dropped") }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    /** "39,00 €" → 39.0. Returns null rather than guessing when the text is unusable. */
    private fun parsePrice(text: String): Double? {
        val cleaned = text.replace(',', '.').filter { it.isDigit() || it == '.' }
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    private companion object {
        /**
         * Nudges the SPA to refresh its results in place. Falls back to a history replace,
         * which re-runs the route's data fetch without discarding the JavaScript context.
         */
        val SOFT_REFRESH_JS = """
            (function () {
              try {
                var url = location.href;
                var sep = url.indexOf('?') >= 0 ? '&' : '?';
                history.replaceState({}, '', url);
                window.dispatchEvent(new Event('popstate'));
                if (window.__radarDealObserver) { return 'observer-alive'; }
                return 'refreshed' + sep;
              } catch (e) { return 'error'; }
            })();
        """.trimIndent()
    }
}
