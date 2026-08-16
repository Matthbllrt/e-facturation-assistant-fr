package com.radardeal.app.monitoring

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cache is what keeps Room off the detection path, and it is read from two threads at once
 * (a polling scan and the WebView's live channel). Both properties are pinned here.
 */
class KnownIdCacheTest {

    @Test
    fun `a cold cache treats everything as unknown`() {
        val cache = KnownIdCache()
        assertThat(cache.isWarm(1L)).isFalse()
        assertThat(cache.filterUnknown(1L, listOf("a", "b"))).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `warming marks the watch and stores its ids`() {
        val cache = KnownIdCache()
        cache.warm(1L, listOf("a", "b", "c"))

        assertThat(cache.isWarm(1L)).isTrue()
        assertThat(cache.size(1L)).isEqualTo(3)
        assertThat(cache.isKnown(1L, "b")).isTrue()
        assertThat(cache.isKnown(1L, "z")).isFalse()
    }

    @Test
    fun `filterUnknown preserves order and returns only the new ids`() {
        val cache = KnownIdCache()
        cache.warm(1L, listOf("a", "c"))

        assertThat(cache.filterUnknown(1L, listOf("a", "b", "c", "d")))
            .containsExactly("b", "d").inOrder()
    }

    @Test
    fun `filterUnknown returns nothing when every card is already known`() {
        val cache = KnownIdCache()
        cache.warm(1L, listOf("a", "b"))
        assertThat(cache.filterUnknown(1L, listOf("a", "b"))).isEmpty()
    }

    @Test
    fun `watches do not share ids`() {
        val cache = KnownIdCache()
        cache.warm(1L, listOf("shared"))
        cache.warm(2L, emptyList())

        assertThat(cache.isKnown(1L, "shared")).isTrue()
        assertThat(cache.isKnown(2L, "shared")).isFalse()
    }

    @Test
    fun `claim succeeds once and only once`() {
        val cache = KnownIdCache()
        cache.warm(1L, emptyList())

        assertThat(cache.claim(1L, "new")).isTrue()
        assertThat(cache.claim(1L, "new")).isFalse()
    }

    /**
     * The race that matters in production: a poll and the live DOM channel seeing the same
     * listing at the same instant. Exactly one of them must be allowed to notify.
     */
    @Test
    fun `concurrent claims of the same id elect a single winner`() {
        val cache = KnownIdCache()
        cache.warm(1L, emptyList())

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val winners = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                start.await()
                if (cache.claim(1L, "contested")) winners.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue()

        assertThat(winners.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent claims of distinct ids all succeed`() {
        val cache = KnownIdCache()
        cache.warm(1L, emptyList())

        val count = 500
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val winners = AtomicInteger(0)

        repeat(count) { i ->
            pool.submit {
                start.await()
                if (cache.claim(1L, "id-$i")) winners.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue()

        assertThat(winners.get()).isEqualTo(count)
        assertThat(cache.size(1L)).isEqualTo(count)
    }

    @Test
    fun `forgetting a watch drops its ids and its warm flag`() {
        val cache = KnownIdCache()
        cache.warm(1L, listOf("a"))

        cache.forget(1L)

        assertThat(cache.isWarm(1L)).isFalse()
        assertThat(cache.isKnown(1L, "a")).isFalse()
    }

    @Test
    fun `addAll tops the set up without disturbing what is there`() {
        val cache = KnownIdCache()
        cache.warm(1L, listOf("a"))
        cache.addAll(1L, listOf("b", "c"))

        assertThat(cache.size(1L)).isEqualTo(3)
        assertThat(cache.isKnown(1L, "a")).isTrue()
    }
}
