package com.radardeal.app.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.radardeal.app.core.RdLog

/**
 * Read-only view of the Vinted session that lives in Android's own WebView cookie store.
 *
 * RadarDeal never asks for, stores or transmits Vinted credentials. The user signs in on
 * Vinted's real login page inside a WebView; Android keeps the resulting cookies in its
 * per-app cookie jar, and this class only ever *reads* whether such cookies exist so the app
 * can tell the user "connexion nécessaire" instead of failing silently.
 */
class VintedSession(private val context: Context) {

    /** Cookie names that indicate Vinted considers this client a real, signed-in browser. */
    private val sessionCookieHints = listOf(
        "_vinted_fr_session",
        "access_token_web",
        "v_udt",
        "anon_id",
    )

    private val fallbackUserAgent =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var cachedUserAgent: String? = null

    /** The device WebView's own user agent, so HTTP requests match the cookies they carry. */
    fun userAgent(): String {
        cachedUserAgent?.let { return it }
        val ua = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrElse {
                RdLog.w("Session", "WebView user agent unavailable, using fallback")
                fallbackUserAgent
            }
            .ifBlank { fallbackUserAgent }
        cachedUserAgent = ua
        return ua
    }

    /** Raw cookie header for [host], or null when Android has none. */
    fun cookieHeader(host: String): String? = runCatching {
        CookieManager.getInstance().getCookie("https://$host/")?.takeIf { it.isNotBlank() }
    }.getOrElse {
        RdLog.w("Session", "cookie store unavailable")
        null
    }

    /** True when at least one Vinted session-ish cookie is present for [host]. */
    fun hasSession(host: String): Boolean {
        val cookies = cookieHeader(host) ?: return false
        return sessionCookieHints.any { cookies.contains(it) }
    }

    /** Persists the cookie jar to disk — called after the user finishes signing in. */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
            .onFailure { RdLog.w("Session", "cookie flush failed") }
    }

    /** Signs the user out locally by dropping the cookies RadarDeal relies on. */
    fun clearCookies(onDone: () -> Unit = {}) {
        runCatching {
            CookieManager.getInstance().removeAllCookies { onDone() }
            CookieManager.getInstance().flush()
        }.onFailure {
            RdLog.w("Session", "cookie clear failed")
            onDone()
        }
    }

    /** Cheap pre-flight so an offline device reports "Erreur réseau" without a socket timeout. */
    fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // Unknown — let the request itself decide.
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
