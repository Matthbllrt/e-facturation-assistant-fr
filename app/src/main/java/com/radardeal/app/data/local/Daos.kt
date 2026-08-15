package com.radardeal.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {

    @Query("SELECT * FROM watches ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches ORDER BY createdAt DESC")
    suspend fun getAll(): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE isActive = 1")
    suspend fun getActive(): List<WatchEntity>

    @Query("SELECT COUNT(*) FROM watches WHERE isActive = 1")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM watches WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WatchEntity?

    @Query("SELECT * FROM watches WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<WatchEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(watch: WatchEntity): Long

    @Update
    suspend fun update(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE watches SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("UPDATE watches SET lastScanAt = :at, lastScanStatus = :status WHERE id = :id")
    suspend fun recordScan(id: Long, at: Long, status: String)

    @Query("UPDATE watches SET baselineDone = 1 WHERE id = :id")
    suspend fun markBaselineDone(id: Long)

    @Query("UPDATE watches SET baselineDone = 0 WHERE id = :id")
    suspend fun resetBaseline(id: Long)
}

/** Row returned by the per-watch aggregate query used on the Veilles screen. */
data class WatchStatsRow(
    val watchId: Long,
    val knownCount: Int,
    val newCount: Int,
)

@Dao
interface ListingDao {

    // --- Reads -----------------------------------------------------------------------------

    @Query("SELECT * FROM listings WHERE watchId = :watchId")
    suspend fun getForWatch(watchId: Long): List<ListingEntity>

    @Query("SELECT itemId FROM listings WHERE watchId = :watchId")
    suspend fun getKnownIds(watchId: Long): List<String>

    @Query("SELECT * FROM listings WHERE watchId = :watchId AND itemId = :itemId LIMIT 1")
    suspend fun get(watchId: Long, itemId: String): ListingEntity?

    @Query("SELECT * FROM listings WHERE watchId = :watchId AND itemId = :itemId LIMIT 1")
    fun observe(watchId: Long, itemId: String): Flow<ListingEntity?>

    @Query("SELECT * FROM listings ORDER BY firstSeenAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ListingEntity>>

    @Query("SELECT * FROM listings WHERE isFavorite = 1 ORDER BY firstSeenAt DESC")
    fun observeFavorites(): Flow<List<ListingEntity>>

    @Query("SELECT COUNT(*) FROM listings WHERE isNew = 1")
    fun observeNewCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM listings WHERE priceDroppedAt IS NOT NULL AND priceDroppedAt > :since")
    fun observePriceDropCount(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM listings WHERE watchId = :watchId")
    suspend fun countForWatch(watchId: Long): Int

    @Query(
        """
        SELECT watchId AS watchId,
               COUNT(*) AS knownCount,
               SUM(CASE WHEN isNew = 1 THEN 1 ELSE 0 END) AS newCount
        FROM listings
        GROUP BY watchId
        """
    )
    fun observeStats(): Flow<List<WatchStatsRow>>

    /** Prices used by the median engine. Only listings that actually carry a price. */
    @Query("SELECT price FROM listings WHERE watchId = :watchId AND price IS NOT NULL")
    suspend fun getPricesForWatch(watchId: Long): List<Double>

    @Query("SELECT price FROM listings WHERE watchId = :watchId AND price IS NOT NULL")
    fun observePricesForWatch(watchId: Long): Flow<List<Double>>

    // --- Writes ----------------------------------------------------------------------------

    @Upsert
    suspend fun upsert(listings: List<ListingEntity>)

    @Upsert
    suspend fun upsert(listing: ListingEntity)

    @Query("UPDATE listings SET isFavorite = :favorite WHERE watchId = :watchId AND itemId = :itemId")
    suspend fun setFavorite(watchId: Long, itemId: String, favorite: Boolean)

    @Query("UPDATE listings SET isNew = 0 WHERE isNew = 1")
    suspend fun clearAllNewFlags()

    @Query("UPDATE listings SET isNew = 0 WHERE watchId = :watchId")
    suspend fun clearNewFlagsForWatch(watchId: Long)

    @Query("UPDATE listings SET isNew = 0 WHERE watchId = :watchId AND itemId = :itemId")
    suspend fun clearNewFlag(watchId: Long, itemId: String)

    /**
     * Keeps the database bounded. Favourites are never dropped, and neither are the newest
     * [keep] listings of the watch.
     */
    @Query(
        """
        DELETE FROM listings
        WHERE watchId = :watchId
          AND isFavorite = 0
          AND itemId NOT IN (
            SELECT itemId FROM listings WHERE watchId = :watchId
            ORDER BY firstSeenAt DESC LIMIT :keep
          )
        """
    )
    suspend fun trimWatch(watchId: Long, keep: Int)

    @Query("DELETE FROM listings WHERE isFavorite = 0")
    suspend fun deleteAllExceptFavorites()

    @Query("DELETE FROM listings")
    suspend fun deleteAll()
}

@Dao
interface PricePointDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(points: List<PricePointEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: PricePointEntity)

    @Query("SELECT * FROM price_points WHERE watchId = :watchId AND itemId = :itemId ORDER BY recordedAt ASC")
    suspend fun getHistory(watchId: Long, itemId: String): List<PricePointEntity>

    @Query("SELECT * FROM price_points WHERE watchId = :watchId AND itemId = :itemId ORDER BY recordedAt ASC")
    fun observeHistory(watchId: Long, itemId: String): Flow<List<PricePointEntity>>

    @Query("DELETE FROM price_points WHERE recordedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM price_points")
    suspend fun deleteAll()
}
