package com.radardeal.app.data.repository

import com.radardeal.app.data.local.ListingDao
import com.radardeal.app.data.local.ListingEntity
import com.radardeal.app.data.local.PricePointDao
import com.radardeal.app.data.local.PricePointEntity
import com.radardeal.app.data.local.toDomain
import com.radardeal.app.domain.model.Listing
import com.radardeal.app.domain.model.PricePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ListingRepository(
    private val listingDao: ListingDao,
    private val pricePointDao: PricePointDao,
) {

    /** How many listings are retained per watch before the oldest non-favourites are dropped. */
    private val retentionPerWatch = 400

    fun observeRecent(limit: Int = 300): Flow<List<Listing>> =
        listingDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeFavorites(): Flow<List<Listing>> =
        listingDao.observeFavorites().map { rows -> rows.map { it.toDomain() } }

    fun observeNewCount(): Flow<Int> = listingDao.observeNewCount()

    fun observePriceDropCount(windowMs: Long = 48 * 3_600_000L): Flow<Int> =
        listingDao.observePriceDropCount(System.currentTimeMillis() - windowMs)

    fun observeListing(watchId: Long, itemId: String): Flow<Listing?> =
        listingDao.observe(watchId, itemId).map { it?.toDomain() }

    fun observePricesForWatch(watchId: Long): Flow<List<Double>> =
        listingDao.observePricesForWatch(watchId)

    fun observePriceHistory(watchId: Long, itemId: String): Flow<List<PricePoint>> =
        pricePointDao.observeHistory(watchId, itemId)
            .map { rows -> rows.map { PricePoint(it.price, it.recordedAt) } }

    suspend fun getForWatch(watchId: Long): List<Listing> =
        listingDao.getForWatch(watchId).map { it.toDomain() }

    suspend fun getPricesForWatch(watchId: Long): List<Double> = listingDao.getPricesForWatch(watchId)

    suspend fun countForWatch(watchId: Long): Int = listingDao.countForWatch(watchId)

    suspend fun persistScanResults(
        entities: List<ListingEntity>,
        pricePoints: List<PricePointEntity>,
    ) {
        if (entities.isNotEmpty()) listingDao.upsert(entities)
        if (pricePoints.isNotEmpty()) pricePointDao.insert(pricePoints)
    }

    suspend fun trim(watchId: Long) = listingDao.trimWatch(watchId, retentionPerWatch)

    suspend fun setFavorite(watchId: Long, itemId: String, favorite: Boolean) =
        listingDao.setFavorite(watchId, itemId, favorite)

    suspend fun clearNewFlag(watchId: Long, itemId: String) = listingDao.clearNewFlag(watchId, itemId)

    suspend fun clearAllNewFlags() = listingDao.clearAllNewFlags()

    suspend fun clearNewFlagsForWatch(watchId: Long) = listingDao.clearNewFlagsForWatch(watchId)

    /** "Effacer l'historique" in Réglages — keeps the veilles and the favourites. */
    suspend fun clearHistoryKeepingFavorites() {
        listingDao.deleteAllExceptFavorites()
        pricePointDao.deleteAll()
    }

    suspend fun purgeOldPricePoints(olderThanMs: Long = 90L * 24 * 3_600_000L) =
        pricePointDao.deleteOlderThan(System.currentTimeMillis() - olderThanMs)
}
