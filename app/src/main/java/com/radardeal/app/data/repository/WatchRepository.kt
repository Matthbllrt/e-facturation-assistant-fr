package com.radardeal.app.data.repository

import com.radardeal.app.data.local.ListingDao
import com.radardeal.app.data.local.WatchDao
import com.radardeal.app.data.local.WatchStatsRow
import com.radardeal.app.data.local.toDomain
import com.radardeal.app.data.local.toEntity
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.domain.model.Watch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** A watch plus the counters shown on the Veilles screen. */
data class WatchWithStats(
    val watch: Watch,
    val knownCount: Int,
    val newCount: Int,
)

class WatchRepository(
    private val watchDao: WatchDao,
    private val listingDao: ListingDao,
) {

    fun observeWatches(): Flow<List<Watch>> =
        watchDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeWatchesWithStats(): Flow<List<WatchWithStats>> =
        combine(watchDao.observeAll(), listingDao.observeStats()) { watches, stats ->
            val byId: Map<Long, WatchStatsRow> = stats.associateBy { it.watchId }
            watches.map { entity ->
                val row = byId[entity.id]
                WatchWithStats(
                    watch = entity.toDomain(),
                    knownCount = row?.knownCount ?: 0,
                    newCount = row?.newCount ?: 0,
                )
            }
        }

    fun observeActiveCount(): Flow<Int> = watchDao.observeActiveCount()

    fun observeWatch(id: Long): Flow<Watch?> = watchDao.observeById(id).map { it?.toDomain() }

    suspend fun getWatch(id: Long): Watch? = watchDao.getById(id)?.toDomain()

    suspend fun getAll(): List<Watch> = watchDao.getAll().map { it.toDomain() }

    suspend fun getActive(): List<Watch> = watchDao.getActive().map { it.toDomain() }

    /** Returns the id assigned by Room. */
    suspend fun create(watch: Watch): Long = watchDao.insert(watch.copy(id = 0L).toEntity())

    suspend fun update(watch: Watch) = watchDao.update(watch.toEntity())

    suspend fun delete(id: Long) = watchDao.deleteById(id)

    suspend fun setActive(id: Long, active: Boolean) = watchDao.setActive(id, active)

    suspend fun recordScan(id: Long, status: ScanStatus, at: Long = System.currentTimeMillis()) =
        watchDao.recordScan(id, at, status.name)

    suspend fun markBaselineDone(id: Long) = watchDao.markBaselineDone(id)

    /**
     * Used when the criteria of a watch change: the previously collected listings no longer
     * describe the same search, so the next scan re-establishes a baseline instead of
     * notifying about every result of the new query.
     */
    suspend fun resetBaseline(id: Long) = watchDao.resetBaseline(id)
}
