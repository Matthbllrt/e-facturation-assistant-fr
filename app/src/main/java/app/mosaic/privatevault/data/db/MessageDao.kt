package app.mosaic.privatevault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.mosaic.privatevault.domain.model.MessageSource
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, local_id ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC, local_id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<MessageEntity>

    @Query("SELECT MAX(timestamp) FROM messages")
    suspend fun latestTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /**
     * IGNORE rather than REPLACE: the unique indices exist to protect rows that
     * are already in the vault, and REPLACE would delete-then-insert, quietly
     * changing the local id of a message the UI is already showing.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(entity: MessageEntity): Long

    @Query(
        """
        UPDATE messages
        SET remote_id = :remoteId,
            source = :source,
            text = COALESCE(:text, text),
            media_kind = :mediaKind,
            status = :status
        WHERE local_id = :localId
        """,
    )
    suspend fun upgrade(
        localId: Long,
        remoteId: String?,
        source: MessageSource,
        text: String?,
        mediaKind: app.mosaic.privatevault.domain.model.MediaKind,
        status: app.mosaic.privatevault.domain.model.DeliveryStatus,
    )

    @Query("UPDATE messages SET status = :status WHERE local_id = :localId")
    suspend fun updateStatus(localId: Long, status: app.mosaic.privatevault.domain.model.DeliveryStatus)

    @Query("SELECT * FROM messages WHERE dedup_key = :dedupKey LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): MessageEntity?

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Transaction
    suspend fun insertOrIgnore(entity: MessageEntity): Long {
        val rowId = insertIgnoring(entity)
        if (rowId != -1L) return rowId
        // Lost the race, or the same key came back after a restart.
        return findByDedupKey(entity.dedupKey)?.localId ?: -1L
    }
}
