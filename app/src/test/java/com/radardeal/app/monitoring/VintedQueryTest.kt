package com.radardeal.app.monitoring

import com.google.common.truth.Truth.assertThat
import com.radardeal.app.domain.model.Watch
import org.junit.Test

/**
 * Covers the translation from what the user asked for into the URL RadarDeal polls — including
 * every way a pasted URL can be wrong, since that field is the app's main source of user error.
 */
class VintedQueryTest {

    private fun watch(
        keyword: String? = null,
        brand: String? = null,
        maxPrice: Double? = null,
        sourceUrl: String? = null,
    ) = Watch(
        id = 1L,
        name = "Test",
        keyword = keyword,
        brand = brand,
        maxPrice = maxPrice,
        sourceUrl = sourceUrl,
    )

    // --- URL validation ---------------------------------------------------------------------

    @Test
    fun `accepts a normal vinted search url`() {
        val result = VintedQuery.checkSearchUrl("https://www.vinted.fr/catalog?search_text=air+max")
        assertThat(result).isInstanceOf(VintedQuery.UrlCheck.Valid::class.java)
        assertThat((result as VintedQuery.UrlCheck.Valid).host).isEqualTo("www.vinted.fr")
    }

    @Test
    fun `accepts other vinted marketplaces`() {
        listOf(
            "https://www.vinted.be/catalog?search_text=x",
            "https://vinted.de/catalog?search_text=x",
            "https://www.vinted.co.uk/catalog?search_text=x",
        ).forEach { url ->
            assertThat(VintedQuery.checkSearchUrl(url)).isInstanceOf(VintedQuery.UrlCheck.Valid::class.java)
        }
    }

    @Test
    fun `adds a missing scheme rather than rejecting the url`() {
        val result = VintedQuery.checkSearchUrl("www.vinted.fr/catalog?search_text=x")
        assertThat(result).isInstanceOf(VintedQuery.UrlCheck.Valid::class.java)
        assertThat((result as VintedQuery.UrlCheck.Valid).normalizedUrl).startsWith("https://")
    }

    @Test
    fun `rejects a non-vinted host`() {
        val result = VintedQuery.checkSearchUrl("https://www.leboncoin.fr/recherche?text=x")
        assertThat(result).isInstanceOf(VintedQuery.UrlCheck.Invalid::class.java)
    }

    @Test
    fun `rejects a look-alike domain`() {
        val result = VintedQuery.checkSearchUrl("https://vinted.fr.evil.example/catalog")
        assertThat(result).isInstanceOf(VintedQuery.UrlCheck.Invalid::class.java)
    }

    @Test
    fun `rejects an item url with a helpful message`() {
        val result = VintedQuery.checkSearchUrl("https://www.vinted.fr/items/123456-nike-air-max")
        assertThat(result).isInstanceOf(VintedQuery.UrlCheck.Invalid::class.java)
        assertThat((result as VintedQuery.UrlCheck.Invalid).reason).contains("annonce")
    }

    @Test
    fun `rejects blank and malformed input without throwing`() {
        listOf(null, "", "   ", "ht!tp:// not a url", "://").forEach { input ->
            assertThat(VintedQuery.checkSearchUrl(input))
                .isInstanceOf(VintedQuery.UrlCheck.Invalid::class.java)
        }
    }

    // --- Parameter forwarding ---------------------------------------------------------------

    @Test
    fun `collapses bracketed array parameters into comma separated values`() {
        val params = VintedQuery.forwardedParams(
            "https://www.vinted.fr/catalog?brand_ids[]=53&brand_ids[]=88&size_ids[]=207"
        )
        assertThat(params["brand_ids"]).isEqualTo("53,88")
        assertThat(params["size_ids"]).isEqualTo("207")
    }

    @Test
    fun `renames catalog to catalog_ids for the api`() {
        val params = VintedQuery.forwardedParams("https://www.vinted.fr/catalog?catalog[]=1231")
        assertThat(params["catalog_ids"]).isEqualTo("1231")
        assertThat(params).doesNotContainKey("catalog")
    }

    @Test
    fun `preserves advanced filters from the pasted url`() {
        val params = VintedQuery.forwardedParams(
            "https://www.vinted.fr/catalog?search_text=air%20max&status_ids[]=2&color_ids[]=1" +
                "&price_to=70&currency=EUR"
        )
        assertThat(params["search_text"]).isEqualTo("air max")
        assertThat(params["status_ids"]).isEqualTo("2")
        assertThat(params["color_ids"]).isEqualTo("1")
        assertThat(params["price_to"]).isEqualTo("70")
        assertThat(params["currency"]).isEqualTo("EUR")
    }

    @Test
    fun `always sorts by newest first`() {
        val params = VintedQuery.forwardedParams(
            "https://www.vinted.fr/catalog?search_text=x&order=price_low_to_high"
        )
        assertThat(params["order"]).isEqualTo("newest_first")
    }

    @Test
    fun `drops paging parameters that would pin the results`() {
        val params = VintedQuery.forwardedParams(
            "https://www.vinted.fr/catalog?search_text=x&page=7&per_page=10"
        )
        assertThat(params).doesNotContainKey("page")
        assertThat(params).doesNotContainKey("per_page")
    }

    // --- Criteria-based watches --------------------------------------------------------------

    @Test
    fun `combines brand and keyword into the search text`() {
        val params = VintedQuery.criteriaParams(watch(keyword = "Air Max 95", brand = "Nike"))
        assertThat(params["search_text"]).isEqualTo("Nike Air Max 95")
    }

    @Test
    fun `writes a whole max price without a decimal point`() {
        val params = VintedQuery.criteriaParams(watch(keyword = "x", maxPrice = 70.0))
        assertThat(params["price_to"]).isEqualTo("70")
    }

    @Test
    fun `omits the price filter when no price was set`() {
        val params = VintedQuery.criteriaParams(watch(keyword = "x"))
        assertThat(params).doesNotContainKey("price_to")
    }

    // --- Endpoints ---------------------------------------------------------------------------

    @Test
    fun `builds an api endpoint on the host of the pasted url`() {
        val endpoints = VintedQuery.endpointsFor(
            watch(sourceUrl = "https://www.vinted.be/catalog?search_text=x")
        )
        assertThat(endpoints.host).isEqualTo("www.vinted.be")
        assertThat(endpoints.apiUrl).startsWith("https://www.vinted.be/api/v2/catalog/items?")
        assertThat(endpoints.browseUrl).isEqualTo("https://www.vinted.be/catalog?search_text=x")
    }

    @Test
    fun `falls back to the configured host for criteria watches`() {
        val endpoints = VintedQuery.endpointsFor(watch(keyword = "x"), fallbackHost = "www.vinted.it")
        assertThat(endpoints.host).isEqualTo("www.vinted.it")
        assertThat(endpoints.apiUrl).contains("www.vinted.it")
    }

    @Test
    fun `treats an invalid source url as criteria rather than failing`() {
        val endpoints = VintedQuery.endpointsFor(
            watch(keyword = "Air Max", sourceUrl = "not a url at all")
        )
        assertThat(endpoints.host).isEqualTo("www.vinted.fr")
        assertThat(endpoints.apiUrl).contains("search_text=Air+Max")
    }

    @Test
    fun `always requests a page size`() {
        val endpoints = VintedQuery.endpointsFor(watch(keyword = "x"))
        assertThat(endpoints.apiUrl).contains("per_page=")
        assertThat(endpoints.apiUrl).contains("page=1")
    }
}
