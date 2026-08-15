package com.radardeal.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.radardeal.app.core.RdLog
import com.radardeal.app.monitoring.VintedParser
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Fallback reader that performs the catalogue request *inside* a WebView, from the Vinted
 * origin itself.
 *
 * Why this exists: the plain HTTP path has to reconstruct by hand what a browser sends. The
 * WebView path does not — the request is issued by `fetch()` running on an already-loaded
 * Vinted page, so it is a genuine same-origin request carrying exactly the session the user
 * established when they signed in. Nothing here defeats a protection: if Vinted answers with a
 * verification page, that is reported as [FetchOutcome.NeedsVerification] and the user is asked
 * to complete it themselves.
 *
 * Robustness rules, in order of importance:
 *  * the WebView is created lazily, only when the HTTP path has already failed;
 *  * every step is bounded by a timeout, so a hung page can never stall the radar;
 *  * a device without a usable WebView provider flips [unavailable] and is never retried,
 *    instead of throwing on each scan;
 *  * the WebView is confined to the Vinted host — any other navigation is refused.
 */
class WebViewVintedFetcher(private val context: Context) {

    private val mutex = Mutex()
    private val tokens = AtomicLong(0L)

    private var webView: WebView? = null
    private var loadedHost: String? = null

    /** Set once creating or driving a WebView has proven impossible on this device. */
    @Volatile
    private var unavailable = false

    private val pending = HashMap<String, CancellableContinuation<BridgeResult?>>()

    private data class BridgeResult(val status: Int, val body: String)

    val isUsable: Boolean get() = !unavailable

    suspend fun fetch(host: String, apiUrl: String, referer: String): FetchOutcome {
        if (unavailable) return FetchOutcome.NetworkError("webview-unavailable")

        return mutex.withLock {
            try {
                withTimeout(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main.immediate) {
                        runFetch(host, apiUrl, referer)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                RdLog.w("WebFetch", "timed out after ${TOTAL_TIMEOUT_MS}ms")
                clearPending()
                FetchOutcome.NetworkError("timeout")
            } catch (t: Throwable) {
                RdLog.e("WebFetch", "unexpected failure", t)
                clearPending()
                FetchOutcome.NetworkError(t.javaClass.simpleName)
            }
        }
    }

    /** Frees the WebView — called when monitoring stops so nothing lingers in memory. */
    fun release() {
        runCatching {
            webView?.let { view ->
                view.stopLoading()
                view.removeJavascriptInterface(BRIDGE_NAME)
                view.destroy()
            }
        }
        webView = null
        loadedHost = null
        clearPending()
    }

    // --- Main-thread internals ---------------------------------------------------------------

    private suspend fun runFetch(host: String, apiUrl: String, referer: String): FetchOutcome {
        val view = obtainWebView() ?: return FetchOutcome.NetworkError("webview-unavailable")

        if (loadedHost != host) {
            val loaded = loadOrigin(view, host)
            if (!loaded) return FetchOutcome.NetworkError("origin-load-failed")
            loadedHost = host
        }

        val result = evaluateFetch(view, apiUrl, referer)
            ?: return FetchOutcome.NetworkError("bridge-no-result")

        runCatching { CookieManager.getInstance().flush() }
        RdLog.d("WebFetch", "status ${result.status}, ${result.body.length} chars")

        val hasCookies = CookieManager.getInstance().getCookie("https://$host/") != null
        return interpret(result, hasCookies)
    }

    private fun interpret(result: BridgeResult, hasCookies: Boolean): FetchOutcome = when {
        result.status == -1 -> FetchOutcome.NetworkError("fetch-rejected")
        result.status == 429 -> FetchOutcome.RateLimited
        result.status == 401 -> FetchOutcome.NeedsLogin
        result.status == 403 -> if (hasCookies) FetchOutcome.NeedsVerification else FetchOutcome.NeedsLogin
        result.status in 500..599 -> FetchOutcome.ServerError(result.status)
        result.status !in 200..299 -> FetchOutcome.ServerError(result.status)
        result.body.isBlank() -> FetchOutcome.ServerError(result.status)
        VintedParser.looksLikeChallenge(result.body) -> FetchOutcome.NeedsVerification
        else -> FetchOutcome.Success(result.body, Transport.WEBVIEW)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView? {
        webView?.let { return it }
        return try {
            val view = WebView(context.applicationContext)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Images and rendering are pure overhead here: only the JSON matters.
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.addJavascriptInterface(Bridge(), BRIDGE_NAME)
            webView = view
            view
        } catch (t: Throwable) {
            // Devices with a disabled or updating WebView provider land here.
            RdLog.e("WebFetch", "WebView unavailable on this device", t)
            unavailable = true
            null
        }
    }

    private suspend fun loadOrigin(view: WebView, host: String): Boolean =
        suspendCancellableCoroutine { cont ->
            var settled = false
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    if (!settled) {
                        settled = true
                        if (cont.isActive) cont.resume(true)
                    }
                }

                override fun onReceivedError(
                    v: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    // Only a failure of the main document should abort the load.
                    if (request?.isForMainFrame == true && !settled) {
                        settled = true
                        RdLog.w("WebFetch", "origin load failed")
                        if (cont.isActive) cont.resume(false)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Confine this WebView to the Vinted marketplace it was opened for.
                    val target = request?.url?.host ?: return false
                    return !target.endsWith(host.removePrefix("www."))
                }
            }
            runCatching { view.loadUrl("https://$host/") }
                .onFailure {
                    if (!settled) {
                        settled = true
                        if (cont.isActive) cont.resume(false)
                    }
                }
            cont.invokeOnCancellation { runCatching { view.stopLoading() } }
        }

    private suspend fun evaluateFetch(view: WebView, apiUrl: String, referer: String): BridgeResult? {
        val token = "t${tokens.incrementAndGet()}"
        return suspendCancellableCoroutine { cont ->
            pending[token] = cont
            cont.invokeOnCancellation { pending.remove(token) }

            val script = buildScript(token, apiUrl, referer)
            runCatching { view.evaluateJavascript(script, null) }
                .onFailure {
                    pending.remove(token)
                    RdLog.w("WebFetch", "evaluateJavascript failed")
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    /**
     * Same-origin `fetch` of the catalogue endpoint, reported back through the bridge. Written
     * as ES5 so that old WebView builds parse it, and fully wrapped so a thrown error still
     * produces a result instead of a silent hang.
     */
    private fun buildScript(token: String, apiUrl: String, referer: String): String {
        val jsToken = JSONObject.quote(token)
        val jsUrl = JSONObject.quote(apiUrl)
        val jsReferer = JSONObject.quote(referer)
        return """
            (function() {
              try {
                fetch($jsUrl, {
                  credentials: 'include',
                  referrer: $jsReferer,
                  headers: { 'Accept': 'application/json, text/plain, */*' }
                }).then(function(response) {
                  return response.text().then(function(text) {
                    $BRIDGE_NAME.onResult($jsToken, response.status, text);
                  });
                }).catch(function(err) {
                  $BRIDGE_NAME.onResult($jsToken, -1, String(err));
                });
              } catch (e) {
                $BRIDGE_NAME.onResult($jsToken, -1, String(e));
              }
            })();
        """.trimIndent()
    }

    private fun clearPending() {
        val snapshot = pending.values.toList()
        pending.clear()
        snapshot.forEach { cont -> if (cont.isActive) cont.resume(null) }
    }

    /**
     * The only surface JavaScript can reach. It accepts a token minted by this class, so a
     * page cannot inject a result for a request that was never made.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun onResult(token: String, status: Int, body: String) {
            // Called on a WebView worker thread — hop back to the main thread before touching
            // the pending map, which is only ever mutated there.
            webView?.post {
                val cont = pending.remove(token) ?: return@post
                if (cont.isActive) cont.resume(BridgeResult(status, body))
            }
        }
    }

    private companion object {
        const val BRIDGE_NAME = "RadarDealBridge"
        const val TOTAL_TIMEOUT_MS = 45_000L
    }
}
