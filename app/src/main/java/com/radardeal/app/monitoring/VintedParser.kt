package com.radardeal.app.monitoring

import com.radardeal.app.core.RdLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A listing exactly as it came off the wire, before RadarDeal diffs it against what it knows. */
data class RawListing(
    val itemId: String,
    val title: String?,
    val brand: String?,
    val size: String?,
    val condition: String?,
    val price: Double?,
    val currency: String?,
    val imageUrl: String?,
    val itemUrl: String?,
)

/**
 * Turns a Vinted catalogue response into [RawListing]s.
 *
 * The guiding rule is that **no input may ever throw**. Vinted changes its payload shape
 * without notice, and a shape RadarDeal does not recognise has to degrade into "0 annonces
 * analysées" — never into a crash. Every field is read through a null-tolerant accessor, and
 * the only hard requirement for keeping a listing is that it carries an id.
 */
object VintedParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Keys under which an array of listings has been observed. */
    private val ITEM_ARRAY_KEYS = listOf("items", "catalogItems", "catalog_items")

    /** Markers of an interstitial page asking the human to prove they are one. */
    private val CHALLENGE_MARKERS = listOf(
        "captcha",
        "datadome",
        "px-captcha",
        "hcaptcha",
        "recaptcha",
        "just a moment",
        "attention required",
        "checking your browser",
        "cf-challenge",
        "/cdn-cgi/challenge-platform",
    )

    /** True when [body] is an anti-bot interstitial rather than a catalogue response. */
    fun looksLikeChallenge(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        // A JSON catalogue response is never a challenge page, and can legitimately contain
        // the word "captcha" inside a listing title.
        if (body.trimStart().startsWith("{") && body.contains("\"items\"")) return false
        val head = body.take(4096).lowercase()
        return CHALLENGE_MARKERS.any { head.contains(it) }
    }

    /** True when the payload is HTML rather than the JSON catalogue we asked for. */
    fun looksLikeHtml(body: String?): Boolean {
        val head = body?.trimStart()?.take(512)?.lowercase() ?: return false
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<?xml")
    }

    /**
     * Parses [body], which may be the catalogue JSON or an HTML page with the same JSON
     * embedded in a `<script>` tag. Returns an empty list when nothing usable was found.
     */
    fun parse(body: String?, host: String = "www.vinted.fr"): List<RawListing> {
        if (body.isNullOrBlank()) return emptyList()
        val direct = runCatching { parseJson(body, host) }.getOrElse {
            RdLog.w("Parser", "direct JSON parse failed: ${it.javaClass.simpleName}")
            emptyList()
        }
        if (direct.isNotEmpty()) return direct

        return runCatching { parseEmbeddedJson(body, host) }.getOrElse {
            RdLog.w("Parser", "embedded JSON parse failed: ${it.javaClass.simpleName}")
            emptyList()
        }
    }

    // --- JSON ------------------------------------------------------------------------------

    private fun parseJson(body: String, host: String): List<RawListing> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array = findItemsArray(root) ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let { runCatching { readListing(it, host) }.getOrNull() }
        }
    }

    /** Depth-limited search for the listings array, wherever the payload happens to nest it. */
    private fun findItemsArray(element: JsonElement, depth: Int = 0): JsonArray? {
        if (depth > 6) return null
        when (element) {
            is JsonArray -> {
                if (element.any { it is JsonObject && looksLikeItem(it) }) return element
                for (child in element.take(20)) {
                    findItemsArray(child, depth + 1)?.let { return it }
                }
            }
            is JsonObject -> {
                for (key in ITEM_ARRAY_KEYS) {
                    val candidate = element[key] as? JsonArray ?: continue
                    if (candidate.any { it is JsonObject && looksLikeItem(it) }) return candidate
                }
                for ((_, child) in element) {
                    findItemsArray(child, depth + 1)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    /** An object is a listing when it has an id and at least one listing-ish field. */
    private fun looksLikeItem(obj: JsonObject): Boolean {
        if (obj["id"] == null) return false
        return obj.keys.any { it in LISTING_MARKER_KEYS }
    }

    private val LISTING_MARKER_KEYS = setOf(
        "title", "brand_title", "size_title", "price", "total_item_price", "photo", "url",
    )

    private fun readListing(obj: JsonObject, host: String): RawListing? {
        val id = stringOf(obj["id"]) ?: return null
        if (id.isBlank()) return null

        // Vinted has shipped the price as a bare number, as a string and as an object; and
        // "total_item_price" (price including buyer protection) came later than "price".
        val priceElement = obj["price"] ?: obj["total_item_price"]
        val price = priceOf(priceElement)
        val currency = currencyOf(priceElement) ?: currencyOf(obj["total_item_price"])

        return RawListing(
            itemId = id,
            title = stringOf(obj["title"]),
            brand = stringOf(obj["brand_title"]) ?: stringOf(obj["brand"]?.objectField("title")),
            size = stringOf(obj["size_title"]) ?: stringOf(obj["size"]?.objectField("title")),
            condition = stringOf(obj["status"]) ?: stringOf(obj["condition"]),
            price = price,
            currency = currency ?: "EUR",
            imageUrl = photoUrlOf(obj),
            itemUrl = absolutize(stringOf(obj["url"]) ?: stringOf(obj["path"]), host, id),
        )
    }

    private fun JsonElement.objectField(name: String): JsonElement? =
        (this as? JsonObject)?.get(name)

    private fun stringOf(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.booleanOrNull != null) return null
        val content = primitive.content
        return content.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun priceOf(element: JsonElement?): Double? = when (element) {
        null -> null
        is JsonPrimitive -> element.content.toDoubleOrNullLenient()
        is JsonObject -> stringOf(element["amount"])?.toDoubleOrNullLenient()
            ?: stringOf(element["value"])?.toDoubleOrNullLenient()
        else -> null
    }

    private fun currencyOf(element: JsonElement?): String? = when (element) {
        is JsonObject -> stringOf(element["currency_code"]) ?: stringOf(element["currency"])
        else -> null
    }

    private fun String.toDoubleOrNullLenient(): Double? {
        val cleaned = trim().replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
        val value = cleaned.toDoubleOrNull() ?: return null
        return if (value.isFinite() && value >= 0.0) value else null
    }

    private fun photoUrlOf(obj: JsonObject): String? {
        val photo = obj["photo"] as? JsonObject
            ?: (obj["photos"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return stringOf(obj["image_url"])

        stringOf(photo["url"])?.let { return it }
        stringOf(photo["full_size_url"])?.let { return it }
        val thumbnails = photo["thumbnails"] as? JsonArray
        // Prefer a mid-size thumbnail: card images are large, but not full resolution.
        val preferred = thumbnails?.lastOrNull() as? JsonObject ?: thumbnails?.firstOrNull() as? JsonObject
        return stringOf(preferred?.get("url"))
    }

    private fun absolutize(url: String?, host: String, id: String): String {
        if (url.isNullOrBlank()) return "https://$host/items/$id"
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> "https://$host$url"
            else -> "https://$host/$url"
        }
    }

    // --- HTML fallback ---------------------------------------------------------------------

    /**
     * Best-effort recovery when Vinted answers with the rendered search page instead of JSON.
     * The page embeds the same catalogue payload inside a script tag; this walks the document
     * looking for a balanced JSON object that contains an items array.
     */
    internal fun parseEmbeddedJson(body: String, host: String): List<RawListing> {
        val marker = "\"items\":["
        var searchFrom = 0
        var attempts = 0
        while (attempts < 5) {
            attempts++
            val markerAt = body.indexOf(marker, searchFrom)
            if (markerAt < 0) return emptyList()
            searchFrom = markerAt + marker.length

            val objectStart = body.lastIndexOf('{', markerAt).takeIf { it >= 0 } ?: continue
            val objectEnd = matchingBrace(body, objectStart) ?: continue
            val candidate = body.substring(objectStart, objectEnd + 1)
            val parsed = runCatching { parseJson(candidate, host) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /** Index of the `}` closing the `{` at [start], honouring strings and escapes. */
    private fun matchingBrace(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        val limit = minOf(text.length, start + 4_000_000)
        while (i < limit) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}
