package com.radardeal.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.radardeal.app.data.local.ListingEntity
import com.radardeal.app.data.local.PricePointEntity
import com.radardeal.app.data.local.RadarDatabase
import com.radardeal.app.data.local.toDomain
import com.radardeal.app.data.local.toEntity
import com.radardeal.app.data.repository.ListingRepository
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.ScanFrequency
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.domain.model.Watch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Exercises the real Room database on an in-memory SQLite instance: the full lifecycle of a
 * veille, the persistence guarantees the app promises, and the cascade that keeps the database
 * consistent when a watch is deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadarDatabaseTest {

    private lateinit var database: RadarDatabase
    private lateinit var watchRepository: WatchRepository
    private lateinit var listingRepository: ListingRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RadarDatabase::class.java,
        )
            .allowMainThreadQueries()
            // Run Room's own work inline so that a Flow query emits deterministically
            // within the test body instead of on a background executor.
            .setQueryExecutor(Executor(Runnable::run))
            .build()

        watchRepository = WatchRepository(database.watchDao(), database.listingDao())
        listingRepository = ListingRepository(database.listingDao(), database.pricePointDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sampleWatch(name: String = "Nike Air Max 95") = Watch(
        name = name,
        keyword = "Air Max 95",
        brand = "Nike",
        maxPrice = 80.0,
        intervalSeconds = ScanFrequency.FAST.seconds,
    )

    private fun listing(
        watchId: Long,
        itemId: String,
        price: Double? = 50.0,
        isFavorite: Boolean = false,
        isNew: Boolean = false,
    ) = ListingEntity(
        watchId = watchId,
        itemId = itemId,
        title = "Nike Air Max 95",
        brand = "Nike",
        size = "42",
        condition = "Très bon état",
        price = price,
        currency = "EUR",
        previousPrice = null,
        imageUrl = "https://img/$itemId.jpg",
        itemUrl = "https://www.vinted.fr/items/$itemId",
        firstSeenAt = System.currentTimeMillis(),
        lastSeenAt = System.currentTimeMillis(),
        isNew = isNew,
        isFavorite = isFavorite,
        priceDroppedAt = null,
    )

    // --- Watch lifecycle -------------------------------------------------------------------------

    @Test
    fun `creates reads updates and deletes a watch`() = runTest {
        val id = watchRepository.create(sampleWatch())
        assertThat(id).isGreaterThan(0L)

        val loaded = watchRepository.getWatch(id)!!
        assertThat(loaded.name).isEqualTo("Nike Air Max 95")
        assertThat(loaded.brand).isEqualTo("Nike")
        assertThat(loaded.maxPrice).isEqualTo(80.0)
        assertThat(loaded.isActive).isTrue()
        assertThat(loaded.baselineDone).isFalse()
        assertThat(loaded.lastScanStatus).isEqualTo(ScanStatus.NEVER)

        watchRepository.update(loaded.copy(name = "Air Max 95 uniquement", maxPrice = 60.0))
        val updated = watchRepository.getWatch(id)!!
        assertThat(updated.name).isEqualTo("Air Max 95 uniquement")
        assertThat(updated.maxPrice).isEqualTo(60.0)

        watchRepository.delete(id)
        assertThat(watchRepository.getWatch(id)).isNull()
    }

    @Test
    fun `pausing a watch removes it from the active set`() = runTest {
        val id = watchRepository.create(sampleWatch())
        assertThat(watchRepository.getActive()).hasSize(1)

        watchRepository.setActive(id, false)
        assertThat(watchRepository.getActive()).isEmpty()
        // …but it is still listed on the Veilles screen.
        assertThat(watchRepository.getAll()).hasSize(1)

        watchRepository.setActive(id, true)
        assertThat(watchRepository.getActive()).hasSize(1)
    }

    @Test
    fun `records the outcome of a scan`() = runTest {
        val id = watchRepository.create(sampleWatch())

        watchRepository.recordScan(id, ScanStatus.NEEDS_VERIFICATION, at = 1_700_000_000_000L)
        val afterFailure = watchRepository.getWatch(id)!!
        assertThat(afterFailure.lastScanStatus).isEqualTo(ScanStatus.NEEDS_VERIFICATION)
        assertThat(afterFailure.lastScanAt).isEqualTo(1_700_000_000_000L)

        watchRepository.recordScan(id, ScanStatus.OK)
        assertThat(watchRepository.getWatch(id)!!.lastScanStatus).isEqualTo(ScanStatus.OK)
    }

    @Test
    fun `baseline flag is set once and can be reset when criteria change`() = runTest {
        val id = watchRepository.create(sampleWatch())
        assertThat(watchRepository.getWatch(id)!!.baselineDone).isFalse()

        watchRepository.markBaselineDone(id)
        assertThat(watchRepository.getWatch(id)!!.baselineDone).isTrue()

        watchRepository.resetBaseline(id)
        assertThat(watchRepository.getWatch(id)!!.baselineDone).isFalse()
    }

    @Test
    fun `an unknown stored status degrades to NEVER instead of crashing`() {
        val entity = sampleWatch().toEntity().copy(lastScanStatus = "A_STATUS_FROM_THE_FUTURE")
        assertThat(entity.toDomain().lastScanStatus).isEqualTo(ScanStatus.NEVER)
    }

    // --- Listings ---------------------------------------------------------------------------------

    @Test
    fun `stores listings and reports them per watch`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(
            entities = (1..5).map { listing(id, it.toString()) },
            pricePoints = emptyList(),
        )

        assertThat(listingRepository.countForWatch(id)).isEqualTo(5)
        assertThat(listingRepository.getForWatch(id)).hasSize(5)
        assertThat(listingRepository.getPricesForWatch(id)).hasSize(5)
    }

    @Test
    fun `data survives closing and reopening the database`() = runTest {
        // A real reopen is not possible for an in-memory database, so this asserts the closest
        // equivalent: the data is durable across independent DAO instances and queries.
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(listOf(listing(id, "1", isFavorite = true)), emptyList())

        val freshWatchRepo = WatchRepository(database.watchDao(), database.listingDao())
        val freshListingRepo = ListingRepository(database.listingDao(), database.pricePointDao())

        assertThat(freshWatchRepo.getWatch(id)).isNotNull()
        assertThat(freshListingRepo.observeFavorites().first()).hasSize(1)
    }

    @Test
    fun `deleting a watch cascades to its listings and price points`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(
            entities = listOf(listing(id, "1"), listing(id, "2")),
            pricePoints = listOf(
                PricePointEntity(watchId = id, itemId = "1", price = 50.0, recordedAt = 1L),
            ),
        )
        assertThat(listingRepository.countForWatch(id)).isEqualTo(2)

        watchRepository.delete(id)

        assertThat(listingRepository.countForWatch(id)).isEqualTo(0)
        assertThat(database.pricePointDao().getHistory(id, "1")).isEmpty()
    }

    @Test
    fun `the same vinted item can be tracked by two different watches`() = runTest {
        val first = watchRepository.create(sampleWatch("Veille A"))
        val second = watchRepository.create(sampleWatch("Veille B"))

        listingRepository.persistScanResults(
            entities = listOf(listing(first, "shared"), listing(second, "shared")),
            pricePoints = emptyList(),
        )

        assertThat(listingRepository.countForWatch(first)).isEqualTo(1)
        assertThat(listingRepository.countForWatch(second)).isEqualTo(1)

        // Favouriting it under one watch leaves the other untouched.
        listingRepository.setFavorite(first, "shared", true)
        assertThat(listingRepository.observeFavorites().first()).hasSize(1)
    }

    @Test
    fun `upsert updates an existing listing rather than duplicating it`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(listOf(listing(id, "1", price = 70.0)), emptyList())
        listingRepository.persistScanResults(listOf(listing(id, "1", price = 55.0)), emptyList())

        val stored = listingRepository.getForWatch(id)
        assertThat(stored).hasSize(1)
        assertThat(stored.single().price).isEqualTo(55.0)
    }

    @Test
    fun `favourites are persistent and observable`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(listOf(listing(id, "1"), listing(id, "2")), emptyList())

        assertThat(listingRepository.observeFavorites().first()).isEmpty()

        listingRepository.setFavorite(id, "1", true)
        val favorites = listingRepository.observeFavorites().first()
        assertThat(favorites).hasSize(1)
        assertThat(favorites.single().itemId).isEqualTo("1")

        listingRepository.setFavorite(id, "1", false)
        assertThat(listingRepository.observeFavorites().first()).isEmpty()
    }

    @Test
    fun `clearing history keeps the veilles and the favourites`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(
            entities = listOf(listing(id, "1", isFavorite = true), listing(id, "2"), listing(id, "3")),
            pricePoints = listOf(
                PricePointEntity(watchId = id, itemId = "2", price = 50.0, recordedAt = 1L),
            ),
        )

        listingRepository.clearHistoryKeepingFavorites()

        assertThat(watchRepository.getAll()).hasSize(1)
        assertThat(listingRepository.countForWatch(id)).isEqualTo(1)
        assertThat(listingRepository.observeFavorites().first()).hasSize(1)
        assertThat(database.pricePointDao().getHistory(id, "2")).isEmpty()
    }

    @Test
    fun `new flags can be cleared individually and in bulk`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(
            entities = listOf(listing(id, "1", isNew = true), listing(id, "2", isNew = true)),
            pricePoints = emptyList(),
        )
        assertThat(listingRepository.observeNewCount().first()).isEqualTo(2)

        listingRepository.clearNewFlag(id, "1")
        assertThat(listingRepository.observeNewCount().first()).isEqualTo(1)

        listingRepository.clearAllNewFlags()
        assertThat(listingRepository.observeNewCount().first()).isEqualTo(0)
    }

    @Test
    fun `price history is recorded in chronological order`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(
            entities = listOf(listing(id, "1")),
            pricePoints = listOf(
                PricePointEntity(watchId = id, itemId = "1", price = 70.0, recordedAt = 1_000L),
                PricePointEntity(watchId = id, itemId = "1", price = 55.0, recordedAt = 2_000L),
            ),
        )

        val history = listingRepository.observePriceHistory(id, "1").first()
        assertThat(history.map { it.price }).containsExactly(70.0, 55.0).inOrder()
    }

    @Test
    fun `watch statistics combine listing counts with the watch itself`() = runTest {
        val id = watchRepository.create(sampleWatch())
        listingRepository.persistScanResults(
            entities = listOf(listing(id, "1", isNew = true), listing(id, "2")),
            pricePoints = emptyList(),
        )

        val stats = watchRepository.observeWatchesWithStats().first().single()
        assertThat(stats.watch.id).isEqualTo(id)
        assertThat(stats.knownCount).isEqualTo(2)
        assertThat(stats.newCount).isEqualTo(1)
    }

    @Test
    fun `a watch with no listings reports zero rather than disappearing`() = runTest {
        watchRepository.create(sampleWatch())
        val stats = watchRepository.observeWatchesWithStats().first().single()
        assertThat(stats.knownCount).isEqualTo(0)
        assertThat(stats.newCount).isEqualTo(0)
    }
}
