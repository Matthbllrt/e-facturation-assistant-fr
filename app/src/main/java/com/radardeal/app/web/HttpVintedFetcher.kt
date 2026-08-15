package com.radardeal.app.web

import com.radardeal.app.core.RdLog
import com.radardeal.app.monitoring.VintedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reads a Vinted catalogue page over plain HTTP, carrying the cookies the user's own WebView
 * session already holds.
 *
 * This is the cheap path: it runs off the main thread, needs no WebView instance and costs
 * almost nothing in memory. It is the same request the Vinted web app makes when you scroll
 * a search page — no protection is worked around, and when Vinted declines to answer the
 * outcome is reported honestly ([FetchOutcome.NeedsLogin], [FetchOutcome.NeedsVerification],
 * [FetchOutcome.RateLimited]) so the user can act on it.
 */
class HttpVintedFetcher(
    private val session: VintedSession,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun fetch(host: String, apiUrl: String, referer: String): FetchOutcome =
        withContext(Dispatchers.IO) {
            if (!session.isOnline()) {
                return@withContext FetchOutcome.NetworkError("offline")
            }

            val cookies = session.cookieHeader(host)
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", session.userAgent())
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .apply { cookies?.let { header("Cookie", it) } }
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    // Cap the read: a challenge page can be large and we only ever need the
                    // catalogue payload, which comfortably fits.
                    val body = runCatching { response.body?.string() }.getOrNull()
                    RdLog.d("HttpFetch", "HTTP $code, ${body?.length ?: 0} chars")

                    interpret(code, body, hasCookies = cookies != null)
                }
            } catch (io: IOException) {
                RdLog.w("HttpFetch", "network failure: ${io.javaClass.simpleName}")
                FetchOutcome.NetworkError(io.javaClass.simpleName)
            } catch (t: Throwable) {
                RdLog.e("HttpFetch", "unexpected failure", t)
                FetchOutcome.NetworkError(t.javaClass.simpleName)
            }
        }

    private fun interpret(code: Int, body: String?, hasCookies: Boolean): FetchOutcome = when {
        code == 429 -> FetchOutcome.RateLimited

        code == 401 -> FetchOutcome.NeedsLogin

        code == 403 -> if (hasCookies) FetchOutcome.NeedsVerification else FetchOutcome.NeedsLogin

        code in 500..599 -> FetchOutcome.ServerError(code)

        code !in 200..299 -> FetchOutcome.ServerError(code)

        body.isNullOrBlank() -> FetchOutcome.ServerError(code)

        VintedParser.looksLikeChallenge(body) -> FetchOutcome.NeedsVerification

        // A 200 that hands back the HTML shell instead of JSON usually means the request was
        // not recognised as a signed-in API call.
        VintedParser.looksLikeHtml(body) && !body.contains("\"items\"") ->
            if (hasCookies) FetchOutcome.NeedsVerification else FetchOutcome.NeedsLogin

        else -> FetchOutcome.Success(body, Transport.HTTP)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
