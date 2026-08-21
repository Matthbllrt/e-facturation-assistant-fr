package app.mosaic.privatevault.domain.model

enum class MessageDirection { INCOMING, OUTGOING }

/** Where a stored message came from. Kept per-message because the two capture
 *  paths have very different fidelity and the UI must not pretend otherwise. */
enum class MessageSource {
    /** Instagram Graph API (professional accounts only). */
    OFFICIAL_API,

    /** Android notification listener — text only, no history, best effort. */
    ANDROID_NOTIFICATION,

    /** Written by Mosaic itself when the user replies from the app. */
    LOCAL_ECHO,
}

enum class MediaKind {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    SHARE,
    STICKER,

    /** A non-text message we know exists but cannot fetch (very common in
     *  notification mode, and for expired media over the API). */
    UNAVAILABLE,
}

enum class DeliveryStatus { RECEIVED, PENDING, SENT, FAILED }

/**
 * One stored message.
 *
 * @param remoteId Instagram's id when the official API supplied it; null for
 *   notification-captured messages, which have no stable server identity.
 * @param dedupKey Stable identity used to reject re-delivery. See
 *   [app.mosaic.privatevault.domain.dedup.MessageDeduplicator].
 */
data class VaultMessage(
    val localId: Long = 0L,
    val remoteId: String? = null,
    val dedupKey: String,
    val timestamp: Long,
    val direction: MessageDirection,
    val text: String?,
    val mediaKind: MediaKind = MediaKind.TEXT,
    val mediaRef: String? = null,
    val status: DeliveryStatus = DeliveryStatus.RECEIVED,
    val source: MessageSource,
    val capturedAt: Long = timestamp,
)
