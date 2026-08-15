package com.radardeal.app.monitoring

import com.radardeal.app.domain.model.Watch
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Translation between what the user asked for and the URLs RadarDeal actually requests.
 *
 * Two shapes are supported:
 *
 *  * **URL-based watches** — the user pastes a Vinted search URL. Its query string already
 *    encodes every advanced filter (catégorie, taille, état, couleur, marque…), so RadarDeal
 *    forwards those parameters unchanged to the catalogue endpoint that the Vinted web app
 *    itself calls when you scroll that same page.
 *  * **Criteria-based watches** — marque / mot-clé / prix max typed in the app, turned into a
 *    plain text search.
 *
 * This file deliberately uses [java.net.URI] rather than `android.net.Uri` so that the whole
 * mapping is covered by ordinary JVM unit tests.
 */
object VintedQuery {

    /** Marketplaces the app accepts a URL from. Matches "vinted.fr", "www.vinted.co.uk", … */
    private val HOST_PATTERN = Regex("^(?:www\\.)?vinted\\.(?:[a-z]{2,3})(?:\\.[a-z]{2,3})?$")

    /** Vinted's own web front-end asks for this page size. */
    private const val PER_PAGE = 48

    /**
     * Query parameters that are named differently on the public search page and on the
     * catalogue endpoint. Anything not listed here is forwarded under its own name.
     */
    private val PARAM_RENAMES = mapOf(
        "catalog" to "catalog_ids",
        "catalog_id" to "catalog_ids",
        "brand_id" to "brand_ids",
        "size_id" to "size_ids",
        "status_id" to "status_ids",
        "color_id" to "color_ids",
        "material_id" to "material_ids",
    )

    /**
     * Parameters we never forward: they either belong to the HTML page only or would pin the
     * request to a stale result set.
     */
    private val DROPPED_PARAMS = setOf("page", "per_page", "time", "disabled_personalization")

    // --- Public API ------------------------------------------------------------------------

    sealed interface UrlCheck {
        data class Valid(val host: String, val normalizedUrl: String) : UrlCheck
        data class Invalid(val reason: String) : UrlCheck
    }

    /**
     * Validates a pasted URL and reports, in plain French, why it cannot be used. Called from
     * the watch editor as the user types.
     */
    fun checkSearchUrl(raw: String?): UrlCheck {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return UrlCheck.Invalid("Collez une URL de recherche Vinted.")

        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return UrlCheck.Invalid("Cette adresse n'est pas une URL valide.")

        val host = uri.host?.lowercase()
            ?: return UrlCheck.Invalid("Cette adresse n'est pas une URL valide.")

        if (!HOST_PATTERN.matches(host)) {
            return UrlCheck.Invalid("Cette URL ne vient pas de Vinted.")
        }

        val path = uri.path.orEmpty()
        if (path.contains("/items/")) {
            return UrlCheck.Invalid(
                "Ceci est l'adresse d'une annonce, pas d'une recherche. " +
                    "Lancez une recherche sur Vinted puis copiez l'adresse de la page de résultats."
            )
        }

        return UrlCheck.Valid(host = host, normalizedUrl = withScheme)
    }

    /**
     * Endpoint RadarDeal polls, plus the browsable page that "Voir sur Vinted" opens.
     */
    data class Endpoints(
        val host: String,
        val apiUrl: String,
        val browseUrl: String,
    )

    /**
     * Builds the endpoints for [watch]. [fallbackHost] is the marketplace configured in
     * Réglages and is used for criteria-based watches, which carry no host of their own.
     */
    fun endpointsFor(watch: Watch, fallbackHost: String = "www.vinted.fr"): Endpoints {
        val fromUrl = watch.sourceUrl?.let { url ->
            (checkSearchUrl(url) as? UrlCheck.Valid)?.let { valid -> valid to url }
        }

        return if (fromUrl != null) {
            val (valid, originalUrl) = fromUrl
            val params = forwardedParams(valid.normalizedUrl)
            Endpoints(
                host = valid.host,
                apiUrl = buildApiUrl(valid.host, params),
                browseUrl = originalUrl,
            )
        } else {
            val host = fallbackHost.ifBlank { "www.vinted.fr" }
            val params = criteriaParams(watch)
            Endpoints(
                host = host,
                apiUrl = buildApiUrl(host, params),
                browseUrl = buildBrowseUrl(host, params),
            )
        }
    }

    // --- Internals -------------------------------------------------------------------------

    /**
     * Reads the query string of a search page and returns the parameters to forward, with
     * `foo[]=1&foo[]=2` collapsed into `foo=1,2` the way the catalogue endpoint expects.
     */
    internal fun forwardedParams(url: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()

        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val rawKey = pair.substring(0, idx)
            val rawValue = pair.substring(idx + 1)
            val key = decode(rawKey).removeSuffix("[]")
            val value = decode(rawValue)
            if (key.isBlank() || value.isBlank()) continue
            if (key in DROPPED_PARAMS) continue
            grouped.getOrPut(PARAM_RENAMES[key] ?: key) { mutableListOf() }.add(value)
        }

        for ((key, values) in grouped) {
            result[key] = values.joinToString(",")
        }
        // Newest first is the only ordering that makes a radar meaningful.
        result["order"] = "newest_first"
        return result
    }

    internal fun criteriaParams(watch: Watch): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        val text = listOfNotNull(
            watch.brand?.trim()?.takeIf { it.isNotEmpty() },
            watch.keyword?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" ")
        if (text.isNotEmpty()) params["search_text"] = text
        watch.minPrice?.let { params["price_from"] = trimNumber(it) }
        watch.maxPrice?.let { params["price_to"] = trimNumber(it) }
        if (watch.minPrice != null || watch.maxPrice != null) params["currency"] = "EUR"
        params["order"] = "newest_first"
        return params
    }

    private fun buildApiUrl(host: String, params: Map<String, String>): String {
        val query = buildString {
            append("page=1&per_page=").append(PER_PAGE)
            for ((key, value) in params) {
                append('&').append(encode(key)).append('=').append(encode(value))
            }
        }
        return "https://$host/api/v2/catalog/items?$query"
    }

    private fun buildBrowseUrl(host: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return if (query.isEmpty()) "https://$host/catalog" else "https://$host/catalog?$query"
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}
