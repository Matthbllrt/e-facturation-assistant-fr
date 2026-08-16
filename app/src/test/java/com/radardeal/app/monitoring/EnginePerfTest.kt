package com.radardeal.app.monitoring

import com.google.common.truth.Truth.assertThat
import com.radardeal.app.domain.model.Listing
import org.junit.Test

/**
 * Benchmarks for the detection hot path.
 *
 * These are not micro-benchmarks in the JMH sense — they run on whatever machine happens to
 * build the project — but they are deterministic enough to catch an order-of-magnitude
 * regression, and the numbers they print are the ones quoted in PERFORMANCE.md.
 *
 * What matters here is the *shape* of the cost, not the absolute milliseconds:
 *
 *  * looking up an already-known id must be O(1) and allocation-free;
 *  * a scan where every card is already known must do essentially no work;
 *  * one new card among a thousand known ones must not trigger a global re-analysis.
 */
class EnginePerfTest {

    private fun raw(id: String, price: Double = 50.0) = RawListing(
        itemId = id,
        title = "Nike Air Max 95",
        brand = "Nike",
        size = "42",
        condition = "Très bon état",
        price = price,
        currency = "EUR",
        imageUrl = "https://img/$id.jpg",
        itemUrl = "https://www.vinted.fr/items/$id",
    )

    private fun stored(id: String, price: Double = 50.0) = Listing(
        itemId = id,
        watchId = 1L,
        title = "Nike Air Max 95",
        brand = "Nike",
        size = "42",
        condition = "Très bon état",
        price = price,
        currency = "EUR",
        imageUrl = "https://img/$id.jpg",
        itemUrl = "https://www.vinted.fr/items/$id",
    )

    /** Runs [block] a few times and reports the best wall time, to damp JIT warm-up noise. */
    private fun measureBest(iterations: Int = 12, block: () -> Unit): Double {
        repeat(3) { block() } // warm up
        var best = Double.MAX_VALUE
        repeat(iterations) {
            val start = System.nanoTime()
            block()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            if (elapsedMs < best) best = elapsedMs
        }
        return best
    }

    private fun report(label: String, ms: Double) {
        println("[RadarPerf] %-46s %8.3f ms".format(label, ms))
    }

    // --- Known-id lookup ------------------------------------------------------------------

    @Test
    fun `known id lookup is constant time on a large set`() {
        val ids = (1..1000).map { it.toString() }
        val cache = KnownIdCache()
        cache.warm(watchId = 1L, ids = ids)

        val hit = measureBest(iterations = 50) {
            repeat(10_000) { i -> cache.isKnown(1L, (i % 1000 + 1).toString()) }
        }
        report("10 000 known-id lookups over 1000 ids", hit)

        // 10 000 hash lookups must stay far below a single frame.
        assertThat(hit).isLessThan(16.0)
        assertThat(cache.isKnown(1L, "500")).isTrue()
        assertThat(cache.isKnown(1L, "not-there")).isFalse()
    }

    @Test
    fun `cache reports unknown ids without touching storage`() {
        val cache = KnownIdCache()
        cache.warm(1L, (1..500).map { it.toString() })

        val newIds = cache.filterUnknown(1L, listOf("1", "2", "9999", "10000"))
        assertThat(newIds).containsExactly("9999", "10000").inOrder()
    }

    // --- Diff cost ------------------------------------------------------------------------

    @Test
    fun `a scan where everything is already known costs almost nothing`() {
        listOf(100, 500, 1000).forEach { size ->
            val known = (1..size).associate { it.toString() to stored(it.toString()) }
            val incoming = (1..size).map { raw(it.toString()) }

            val ms = measureBest {
                ScanDiff.diff(1L, known, incoming, baselineDone = true, now = 1_700_000_000_000L)
            }
            report("diff, $size cards, all already known", ms)
            assertThat(ms).isLessThan(60.0)
        }
    }

    @Test
    fun `one new card among a thousand known ones is detected cheaply`() {
        val known = (1..1000).associate { it.toString() to stored(it.toString()) }
        val incoming = (1..1000).map { raw(it.toString()) } + raw("brand-new")

        var newCount = 0
        val ms = measureBest {
            val outcome = ScanDiff.diff(
                1L, known, incoming, baselineDone = true, now = 1_700_000_000_000L,
            )
            newCount = outcome.newListings.size
        }
        report("diff, 1000 known + 1 new", ms)

        assertThat(newCount).isEqualTo(1)
        assertThat(ms).isLessThan(60.0)
    }

    /**
     * The point of the incremental path: with the id cache in front of the diff, a scan that
     * brings nothing new never builds a listing object at all.
     */
    @Test
    fun `incremental pre-filter skips the diff entirely when nothing is new`() {
        val ids = (1..1000).map { it.toString() }
        val cache = KnownIdCache()
        cache.warm(1L, ids)
        val incoming = ids.map { raw(it) }

        val ms = measureBest(iterations = 50) {
            val unknown = cache.filterUnknown(1L, incoming.map { it.itemId })
            check(unknown.isEmpty())
        }
        report("pre-filter, 1000 cards, none new", ms)

        // This is the common case in production and it must be effectively free.
        assertThat(ms).isLessThan(5.0)
    }

    // --- Deal engine ----------------------------------------------------------------------

    @Test
    fun `median calculation over a thousand prices stays off the critical path budget`() {
        val prices = (1..1000).map { 20.0 + (it % 120) }

        val ms = measureBest {
            DealEngine.assess(price = 35.0, allPrices = prices)
        }
        report("deal assessment over 1000 prices", ms)
        assertThat(ms).isLessThan(30.0)
    }

    // --- Parser ---------------------------------------------------------------------------

    @Test
    fun `parsing a full catalogue page`() {
        val items = (1..96).joinToString(",") { i ->
            """{"id":$i,"title":"Nike Air Max 95","brand_title":"Nike","size_title":"42",
               "status":"Très bon état","price":{"amount":"${20 + i}.0","currency_code":"EUR"},
               "url":"https://www.vinted.fr/items/$i-nike","photo":{"url":"https://img/$i.jpg"}}"""
        }
        val payload = """{"items":[$items]}"""

        var parsed = 0
        val ms = measureBest {
            parsed = VintedParser.parse(payload).size
        }
        report("parse 96-item catalogue JSON", ms)

        assertThat(parsed).isEqualTo(96)
        assertThat(ms).isLessThan(60.0)
    }
}
