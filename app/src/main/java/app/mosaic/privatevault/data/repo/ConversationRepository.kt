package app.mosaic.privatevault.data.repo

import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.core.time.Clock
import app.mosaic.privatevault.data.db.MessageDao
import app.mosaic.privatevault.data.db.toDomain
import app.mosaic.privatevault.data.db.toEntity
import app.mosaic.privatevault.domain.dedup.MessageDeduplicator
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import app.mosaic.privatevault.domain.model.VaultMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A message as captured, before Mosaic decides what to do with it. */
data class MessageCapture(
    val remoteId: String? = null,
    val timestamp: Long,
    val direction: MessageDirection,
    val text: String?,
    val mediaKind: MediaKind = MediaKind.TEXT,
    val mediaRef: String? = null,
    val status: DeliveryStatus = DeliveryStatus.RECEIVED,
    val source: MessageSource,
)

/**
 * The vault's only write path.
 *
 * Its contract, and the reason it is worth a class of its own: a message that
 * has been stored is never removed by anything other than an explicit erase.
 * Instagram deleting a message, a re-sync returning less history, a failed
 * send — none of them delete local rows.
 */
class ConversationRepository(
    private val dao: MessageDao,
    private val clock: Clock = Clock.System,
    private val deduplicator: MessageDeduplicator = MessageDeduplicator(),
) {

    sealed interface Outcome {
        data class Stored(val localId: Long) : Outcome
        data class Duplicate(val localId: Long) : Outcome
        data class Upgraded(val localId: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun observeMessages(): Flow<List<VaultMessage>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun record(capture: MessageCapture): Outcome {
        val dedupKey = deduplicator.keyFor(
            remoteId = capture.remoteId,
            direction = capture.direction,
            text = capture.text,
            timestamp = capture.timestamp,
        )
        val candidate = VaultMessage(
            remoteId = capture.remoteId,
            dedupKey = dedupKey,
            timestamp = capture.timestamp,
            direction = capture.direction,
            text = capture.text,
            mediaKind = capture.mediaKind,
            mediaRef = capture.mediaRef,
            status = capture.status,
            source = capture.source,
            capturedAt = clock.nowMillis(),
        )

        return try {
            val window = dao.since(capture.timestamp - COMPARISON_WINDOW_MILLIS).map { it.toDomain() }
            when (val decision = deduplicator.decide(candidate, window)) {
                is MessageDeduplicator.Decision.Skip -> {
                    SafeLog.d("vault.duplicate_dropped source=${capture.source}")
                    Outcome.Duplicate(decision.existingLocalId)
                }

                is MessageDeduplicator.Decision.Merge -> {
                    dao.upgrade(
                        localId = decision.existingLocalId,
                        remoteId = candidate.remoteId,
                        source = candidate.source,
                        text = candidate.text,
                        mediaKind = candidate.mediaKind,
                        status = candidate.status,
                    )
                    SafeLog.d("vault.message_upgraded source=${capture.source}")
                    Outcome.Upgraded(decision.existingLocalId)
                }

                MessageDeduplicator.Decision.Insert -> {
                    val id = dao.insertOrIgnore(candidate.toEntity())
                    if (id <= 0L) {
                        Outcome.Duplicate(id)
                    } else {
                        SafeLog.d("vault.message_stored source=${capture.source}")
                        Outcome.Stored(id)
                    }
                }
            }
        } catch (error: Exception) {
            // Never swallowed: a capture that could not be written is the one
            // failure mode that silently loses a message.
            SafeLog.e("vault.record_failed", error)
            Outcome.Failed(error::class.java.simpleName)
        }
    }

    suspend fun markStatus(localId: Long, status: DeliveryStatus) {
        runCatching { dao.updateStatus(localId, status) }
            .onFailure { SafeLog.e("vault.status_update_failed", it) }
    }

    suspend fun lastActivityMillis(): Long? = runCatching { dao.latestTimestamp() }.getOrNull()

    suspend fun messageCount(): Int = runCatching { dao.count() }.getOrDefault(0)

    /** Used only by the explicit erase flow. */
    suspend fun eraseAll() = dao.deleteAll()

    private companion object {
        /**
         * How far back [record] looks for a near-duplicate. Wider than the
         * deduplicator's own window so that clock skew between a notification
         * and the API copy cannot hide an existing row.
         */
        const val COMPARISON_WINDOW_MILLIS = 10 * 60_000L
    }
}
