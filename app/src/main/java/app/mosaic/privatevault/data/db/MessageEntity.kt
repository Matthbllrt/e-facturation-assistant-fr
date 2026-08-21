package app.mosaic.privatevault.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import app.mosaic.privatevault.domain.model.VaultMessage

@Entity(
    tableName = "messages",
    indices = [
        // The last line of defence against duplicates: even if the in-memory
        // check is bypassed by a process restart, the database refuses the row.
        Index(value = ["dedup_key"], unique = true),
        Index(value = ["timestamp"]),
        Index(value = ["remote_id"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id") val localId: Long = 0L,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "direction") val direction: MessageDirection,
    @ColumnInfo(name = "text") val text: String?,
    @ColumnInfo(name = "media_kind") val mediaKind: MediaKind,
    @ColumnInfo(name = "media_ref") val mediaRef: String?,
    @ColumnInfo(name = "status") val status: DeliveryStatus,
    @ColumnInfo(name = "source") val source: MessageSource,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
)

fun MessageEntity.toDomain(): VaultMessage = VaultMessage(
    localId = localId,
    remoteId = remoteId,
    dedupKey = dedupKey,
    timestamp = timestamp,
    direction = direction,
    text = text,
    mediaKind = mediaKind,
    mediaRef = mediaRef,
    status = status,
    source = source,
    capturedAt = capturedAt,
)

fun VaultMessage.toEntity(): MessageEntity = MessageEntity(
    localId = localId,
    remoteId = remoteId,
    dedupKey = dedupKey,
    timestamp = timestamp,
    direction = direction,
    text = text,
    mediaKind = mediaKind,
    mediaRef = mediaRef,
    status = status,
    source = source,
    capturedAt = capturedAt,
)
