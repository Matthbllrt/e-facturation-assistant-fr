package com.radardeal.app.monitoring

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The parser is the part of RadarDeal most exposed to change outside its control: Vinted can
 * reshape its payload at any time. These tests pin the two rules that matter — read every shape
 * we know of, and **never throw** on one we do not.
 */
class VintedParserTest {

    private val fullPayload = """
        {
          "items": [
            {
              "id": 4821337,
              "title": "Nike Air Max 95 OG",
              "brand_title": "Nike",
              "size_title": "42",
              "status": "Très bon état",
              "price": { "amount": "39.0", "currency_code": "EUR" },
              "url": "https://www.vinted.fr/items/4821337-nike-air-max-95",
              "photo": { "url": "https://images.vinted.net/photo1.jpg" }
            },
            {
              "id": 4821338,
              "title": "Air Max 95 Neon",
              "brand_title": "Nike",
              "size_title": "43",
              "status": "Bon état",
              "price": { "amount": "72.50", "currency_code": "EUR" },
              "url": "https://www.vinted.fr/items/4821338-air-max-95-neon",
              "photo": { "url": "https://images.vinted.net/photo2.jpg" }
            }
          ],
          "pagination": { "current_page": 1, "total_pages": 12 }
        }
    """.trimIndent()

    @Test
    fun `parses the standard catalogue payload`() {
        val items = VintedParser.parse(fullPayload)
        assertThat(items).hasSize(2)

        val first = items[0]
        assertThat(first.itemId).isEqualTo("4821337")
        assertThat(first.title).isEqualTo("Nike Air Max 95 OG")
        assertThat(first.brand).isEqualTo("Nike")
        assertThat(first.size).isEqualTo("42")
        assertThat(first.condition).isEqualTo("Très bon état")
        assertThat(first.price).isEqualTo(39.0)
        assertThat(first.currency).isEqualTo("EUR")
        assertThat(first.imageUrl).isEqualTo("https://images.vinted.net/photo1.jpg")
        assertThat(first.itemUrl).isEqualTo("https://www.vinted.fr/items/4821337-nike-air-max-95")
    }

    @Test
    fun `reads a price given as a bare number or a string`() {
        val payload = """
            {"items":[
              {"id":1,"title":"a","price":42},
              {"id":2,"title":"b","price":"37.50"},
              {"id":3,"title":"c","price":"29,90"}
            ]}
        """.trimIndent()
        val items = VintedParser.parse(payload)
        assertThat(items.map { it.price }).containsExactly(42.0, 37.5, 29.9).inOrder()
    }

    @Test
    fun `falls back to total_item_price when price is absent`() {
        val payload = """
            {"items":[{"id":9,"title":"a","total_item_price":{"amount":"55.0","currency_code":"EUR"}}]}
        """.trimIndent()
        assertThat(VintedParser.parse(payload).single().price).isEqualTo(55.0)
    }

    @Test
    fun `keeps a listing that is missing everything except its id`() {
        val payload = """{"items":[{"id":777,"title":"Sans détails"}]}"""
        val item = VintedParser.parse(payload).single()

        assertThat(item.itemId).isEqualTo("777")
        assertThat(item.price).isNull()
        assertThat(item.brand).isNull()
        assertThat(item.size).isNull()
        assertThat(item.imageUrl).isNull()
        // A URL can always be reconstructed from the id, so the card stays actionable.
        assertThat(item.itemUrl).isEqualTo("https://www.vinted.fr/items/777")
    }

    @Test
    fun `drops entries without an id instead of failing the whole scan`() {
        val payload = """{"items":[{"title":"orphan"},{"id":5,"title":"kept"}]}"""
        val items = VintedParser.parse(payload)
        assertThat(items).hasSize(1)
        assertThat(items.single().itemId).isEqualTo("5")
    }

    @Test
    fun `resolves a relative item url against the marketplace host`() {
        val payload = """{"items":[{"id":5,"title":"a","url":"/items/5-truc"}]}"""
        val item = VintedParser.parse(payload, host = "www.vinted.be").single()
        assertThat(item.itemUrl).isEqualTo("https://www.vinted.be/items/5-truc")
    }

    @Test
    fun `uses a thumbnail when no direct photo url is given`() {
        val payload = """
            {"items":[{"id":5,"title":"a","photo":{"thumbnails":[
              {"url":"https://img/small.jpg"},{"url":"https://img/large.jpg"}]}}]}
        """.trimIndent()
        assertThat(VintedParser.parse(payload).single().imageUrl).isNotEmpty()
    }

    @Test
    fun `returns empty for payloads it cannot understand instead of throwing`() {
        listOf(
            null,
            "",
            "   ",
            "not json at all",
            "{",
            "[]",
            "{}",
            """{"items":[]}""",
            """{"items":"unexpectedly a string"}""",
            """{"data":{"nested":{"too":{"deep":{"to":{"find":{"items":[{"id":1,"title":"x"}]}}}}}}}""",
        ).forEach { payload ->
            val result = VintedParser.parse(payload)
            assertThat(result).isNotNull()
        }
    }

    @Test
    fun `finds the listings array even when it is nested`() {
        val payload = """{"data":{"catalog":{"items":[{"id":8,"title":"nested","price":12}]}}}"""
        assertThat(VintedParser.parse(payload).single().itemId).isEqualTo("8")
    }

    // --- Challenge / HTML detection ------------------------------------------------------------

    @Test
    fun `detects an anti-bot interstitial`() {
        listOf(
            "<html><head><title>Just a moment...</title></head><body>cf-challenge</body></html>",
            "<html><body><div id=\"px-captcha\"></div></body></html>",
            "<!DOCTYPE html><html><body>Checking your browser before accessing</body></html>",
        ).forEach { body ->
            assertThat(VintedParser.looksLikeChallenge(body)).isTrue()
        }
    }

    @Test
    fun `does not mistake a listing titled captcha for a challenge`() {
        val payload = """{"items":[{"id":1,"title":"T-shirt CAPTCHA vintage","price":10}]}"""
        assertThat(VintedParser.looksLikeChallenge(payload)).isFalse()
        assertThat(VintedParser.parse(payload)).hasSize(1)
    }

    @Test
    fun `recognises html`() {
        assertThat(VintedParser.looksLikeHtml("<!DOCTYPE html><html>…")).isTrue()
        assertThat(VintedParser.looksLikeHtml(fullPayload)).isFalse()
        assertThat(VintedParser.looksLikeHtml(null)).isFalse()
    }

    @Test
    fun `recovers the catalogue embedded in an html page`() {
        val html = """
            <!DOCTYPE html><html><head><title>Vinted</title></head><body>
            <script>window.__DATA__ = {"catalog":{"items":[
              {"id":314,"title":"Trouvé dans le HTML","price":{"amount":"25.0"}}
            ]},"other":"value"};</script>
            </body></html>
        """.trimIndent()

        val items = VintedParser.parse(html)
        assertThat(items).hasSize(1)
        assertThat(items.single().itemId).isEqualTo("314")
        assertThat(items.single().price).isEqualTo(25.0)
    }

    @Test
    fun `handles an html page with braces inside strings`() {
        val html = """
            <html><body><script>var x = {"note":"a } brace \" inside","items":[
              {"id":99,"title":"ok","price":5}]};</script></body></html>
        """.trimIndent()
        assertThat(VintedParser.parse(html).single().itemId).isEqualTo("99")
    }
}
