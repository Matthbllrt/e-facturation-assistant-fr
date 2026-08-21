package app.mosaic.privatevault.domain.dedup

import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import app.mosaic.privatevault.domain.model.VaultMessage
import java.text.Normalizer

/**
 * Decides whether an incoming capture is new, already known, or the better
 * version of something already stored.
 *
 * Three things cause the same message to arrive twice:
 *   - Instagram re-posts a notification when the conversation is updated;
 *   - a webhook and a polling sync both deliver it over the API;
 *   - a message captured from a notification is later seen again over the API,
 *     this time with a real Instagram id.
 *
 * The third case must not become two bubbles, and must not throw away the
 * server id either — hence [Decision.Merge].
 */
class MessageDeduplicator(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {

    sealed interface Decision {
        /** Nothing like it is stored: insert as-is. */
        data object Insert : Decision

        /** Already stored; drop the capture. */
        data class Skip(val existingLocalId: Long) : Decision

        /**
         * A notification-captured row is the same message, now seen over the
         * API: keep the row, adopt the remote id and the better metadata.
         */
        data class Merge(val existingLocalId: Long) : Decision
    }

    fun decide(candidate: VaultMessage, recent: List<VaultMessage>): Decision {
        candidate.remoteId?.let { remoteId ->
            recent.firstOrNull { it.remoteId == remoteId }?.let { return Decision.Skip(it.localId) }
        }

        val match = recent.firstOrNull { existing -> sameMessage(candidate, existing) }
            ?: return Decision.Insert

        val upgradesRemoteId = candidate.remoteId != null && match.remoteId == null
        val upgradesSource = candidate.source == MessageSource.OFFICIAL_API &&
            match.source != MessageSource.OFFICIAL_API
        return if (upgradesRemoteId || upgradesSource) {
            Decision.Merge(match.localId)
        } else {
            Decision.Skip(match.localId)
        }
    }

    private fun sameMessage(candidate: VaultMessage, existing: VaultMessage): Boolean {
        if (candidate.direction != existing.direction) return false
        if (kotlin.math.abs(candidate.timestamp - existing.timestamp) > windowMillis) return false
        val a = normalize(candidate.text)
        val b = normalize(existing.text)
        // Two media-only captures within the window are indistinguishable from
        // one another; treating them as the same is the safer error, because a
        // duplicated "photo" bubble is noise while a lost one is a lost message.
        if (a == null && b == null) return candidate.mediaKind == existing.mediaKind
        return a != null && a == b
    }

    /**
     * The stable identity written to the database's unique index. For API
     * messages it is the Instagram id; for notification captures it is a
     * content+time-bucket key, which stops the exact same notification being
     * re-inserted after a process restart, when [decide] has no in-memory
     * history to compare against.
     */
    fun keyFor(
        remoteId: String?,
        direction: MessageDirection,
        text: String?,
        timestamp: Long,
    ): String {
        if (remoteId != null) return "r:$remoteId"
        val bucket = timestamp / windowMillis
        val body = normalize(text) ?: "<media>"
        return "n:${direction.name}:$bucket:${stableHash(body)}"
    }

    private fun normalize(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return decomposed.trim().replace(WHITESPACE, " ").lowercase()
    }

    private fun stableHash(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * 90 s. Long enough to cover the gap between a notification and the API
         * copy of the same message, short enough that genuinely sending the same
         * short text twice in a conversation still shows twice — unless it
         * happens inside a minute and a half, which is the accepted trade.
         */
        const val DEFAULT_WINDOW_MILLIS = 90_000L

        private val WHITESPACE = Regex("\\s+")
    }
}
