package com.radardeal.app.monitoring

import com.google.common.truth.Truth.assertThat
import com.radardeal.app.domain.model.Listing
import org.junit.Test

/**
 * The comparison engine — the heart of the product. These tests encode the exact behaviour the
 * spec describes: the first scan is a silent baseline, the next one reports only what is
 * genuinely new, and a price that goes down is announced once.
 */
class ScanDiffTest {

    private val watchId = 42L
    private val now = 1_700_000_000_000L

    private fun raw(
        id: String,
        price: Double? = 50.0,
        title: String? = "Nike Air Max 95",
        brand: String? = "Nike",
        size: String? = "42",
        imageUrl: String? = "https://img/$id.jpg",
    ) = RawListing(
        itemId = id,
        title = title,
        brand = brand,
        size = size,
        condition = "Très bon état",
        price = price,
        currency = "EUR",
        imageUrl = imageUrl,
        itemUrl = "https://www.vinted.fr/items/$id",
    )

    private fun known(vararg listings: Listing): Map<String, Listing> =
        listings.associateBy { it.itemId }

    private fun stored(
        id: String,
        price: Double? = 50.0,
        isFavorite: Boolean = false,
        previousPrice: Double? = null,
        priceDroppedAt: Long? = null,
        firstSeenAt: Long = now - 100_000,
    ) = Listing(
        itemId = id,
        watchId = watchId,
        title = "Nike Air Max 95",
        brand = "Nike",
        size = "42",
        condition = "Très bon état",
        price = price,
        currency = "EUR",
        previousPrice = previousPrice,
        imageUrl = "https://img/$id.jpg",
        itemUrl = "https://www.vinted.fr/items/$id",
        firstSeenAt = firstSeenAt,
        lastSeenAt = firstSeenAt,
        isFavorite = isFavorite,
        priceDroppedAt = priceDroppedAt,
    )

    // --- Baseline -----------------------------------------------------------------------------

    @Test
    fun `the first scan stores everything and flags nothing as new`() {
        val incoming = (1..52).map { raw(it.toString()) }

        val result = ScanDiff.diff(watchId, emptyMap(), incoming, baselineDone = false, now = now)

        assertThat(result.entities).hasSize(52)
        assertThat(result.newListings).isEmpty()
        assertThat(result.entities.none { it.isNew }).isTrue()
        assertThat(result.priceDrops).isEmpty()
    }

    @Test
    fun `the second scan reports only the listings that were not there before`() {
        val baseline = (1..52).map { stored(it.toString()) }
        val incoming = (1..54).map { raw(it.toString()) }

        val result = ScanDiff.diff(watchId, known(*baseline.toTypedArray()), incoming, baselineDone = true, now = now)

        assertThat(result.newListings).hasSize(2)
        assertThat(result.newListings.map { it.itemId }).containsExactly("53", "54")
        assertThat(result.entities.filter { it.isNew }).hasSize(2)
    }

    @Test
    fun `a new listing records its first seen time and is marked new`() {
        val result = ScanDiff.diff(watchId, emptyMap(), listOf(raw("1")), baselineDone = true, now = now)

        val listing = result.newListings.single()
        assertThat(listing.isNew).isTrue()
        assertThat(listing.firstSeenAt).isEqualTo(now)
        assertThat(listing.lastSeenAt).isEqualTo(now)
    }

    // --- Price drops ---------------------------------------------------------------------------

    @Test
    fun `detects a price drop and records the previous price`() {
        val result = ScanDiff.diff(
            watchId,
            known(stored("1", price = 70.0)),
            listOf(raw("1", price = 55.0)),
            baselineDone = true,
            now = now,
        )

        val drop = result.priceDrops.single()
        assertThat(drop.oldPrice).isEqualTo(70.0)
        assertThat(drop.newPrice).isEqualTo(55.0)
        assertThat(drop.ratio).isWithin(0.001).of(0.214)

        val entity = result.entities.single()
        assertThat(entity.price).isEqualTo(55.0)
        assertThat(entity.previousPrice).isEqualTo(70.0)
        assertThat(entity.priceDroppedAt).isEqualTo(now)
    }

    @Test
    fun `a price increase is recorded but never announced as a drop`() {
        val result = ScanDiff.diff(
            watchId,
            known(stored("1", price = 40.0)),
            listOf(raw("1", price = 60.0)),
            baselineDone = true,
            now = now,
        )

        assertThat(result.priceDrops).isEmpty()
        assertThat(result.entities.single().previousPrice).isEqualTo(40.0)
        assertThat(result.entities.single().priceDroppedAt).isNull()
    }

    @Test
    fun `an unchanged price produces no drop and no new price point`() {
        val result = ScanDiff.diff(
            watchId,
            known(stored("1", price = 50.0)),
            listOf(raw("1", price = 50.0)),
            baselineDone = true,
            now = now,
        )

        assertThat(result.priceDrops).isEmpty()
        assertThat(result.pricePoints).isEmpty()
    }

    @Test
    fun `an old price drop is not announced again on the next scan`() {
        // The listing already carries the trace of a drop detected earlier.
        val previouslyDropped = stored(
            "1",
            price = 55.0,
            previousPrice = 70.0,
            priceDroppedAt = now - 60_000,
        )

        val result = ScanDiff.diff(
            watchId,
            known(previouslyDropped),
            listOf(raw("1", price = 55.0)),
            baselineDone = true,
            now = now,
        )

        assertThat(result.priceDrops).isEmpty()
        // …but the card still shows it.
        assertThat(result.entities.single().previousPrice).isEqualTo(70.0)
    }

    @Test
    fun `records a price point whenever the price actually changes`() {
        val result = ScanDiff.diff(
            watchId,
            known(stored("1", price = 70.0)),
            listOf(raw("1", price = 55.0)),
            baselineDone = true,
            now = now,
        )

        val point = result.pricePoints.single()
        assertThat(point.price).isEqualTo(55.0)
        assertThat(point.recordedAt).isEqualTo(now)
        assertThat(point.itemId).isEqualTo("1")
    }

    // --- Defensive merging ----------------------------------------------------------------------

    @Test
    fun `a scan that returns no price keeps the price already known`() {
        val result = ScanDiff.diff(
            watchId,
            known(stored("1", price = 45.0)),
            listOf(raw("1", price = null)),
            baselineDone = true,
            now = now,
        )

        assertThat(result.entities.single().price).isEqualTo(45.0)
        assertThat(result.priceDrops).isEmpty()
    }

    @Test
    fun `missing fields never erase what was already stored`() {
        val result = ScanDiff.diff(
            watchId,
            known(stored("1")),
            listOf(raw("1", title = null, brand = null, size = null, imageUrl = null)),
            baselineDone = true,
            now = now,
        )

        val entity = result.entities.single()
        assertThat(entity.title).isEqualTo("Nike Air Max 95")
        assertThat(entity.brand).isEqualTo("Nike")
        assertThat(entity.size).isEqualTo("42")
        assertThat(entity.imageUrl).isEqualTo("https://img/1.jpg")
    }

    @Test
    fun `favourite status and first seen time survive a rescan`() {
        val firstSeen = now - 999_000
        val result = ScanDiff.diff(
            watchId,
            known(stored("1", isFavorite = true, firstSeenAt = firstSeen)),
            listOf(raw("1", price = 30.0)),
            baselineDone = true,
            now = now,
        )

        val entity = result.entities.single()
        assertThat(entity.isFavorite).isTrue()
        assertThat(entity.firstSeenAt).isEqualTo(firstSeen)
        assertThat(entity.lastSeenAt).isEqualTo(now)
    }

    @Test
    fun `a duplicated id in one response is stored once`() {
        val result = ScanDiff.diff(
            watchId,
            emptyMap(),
            listOf(raw("1"), raw("1"), raw("2")),
            baselineDone = true,
            now = now,
        )

        assertThat(result.entities).hasSize(2)
    }

    @Test
    fun `blank ids are ignored`() {
        val result = ScanDiff.diff(
            watchId,
            emptyMap(),
            listOf(raw(""), raw("  "), raw("3")),
            baselineDone = true,
            now = now,
        )

        assertThat(result.entities.map { it.itemId }).containsExactly("3")
    }

    @Test
    fun `an empty response changes nothing`() {
        val result = ScanDiff.diff(watchId, known(stored("1")), emptyList(), baselineDone = true, now = now)

        assertThat(result.isEmpty).isTrue()
        assertThat(result.newListings).isEmpty()
        assertThat(result.priceDrops).isEmpty()
    }

    @Test
    fun `a listing that disappears from the results is left untouched`() {
        // Vinted stops returning "1"; RadarDeal keeps what it knows rather than deleting it.
        val result = ScanDiff.diff(
            watchId,
            known(stored("1"), stored("2")),
            listOf(raw("2")),
            baselineDone = true,
            now = now,
        )

        assertThat(result.entities.map { it.itemId }).containsExactly("2")
    }
}
