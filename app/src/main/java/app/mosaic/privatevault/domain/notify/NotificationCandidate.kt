package app.mosaic.privatevault.domain.notify

/**
 * A status-bar notification reduced to the fields Mosaic is allowed to look at.
 *
 * Keeping this a plain data class is what makes the filtering rules unit
 * testable — [NotificationMatcher] never touches Android types.
 */
data class NotificationCandidate(
    val packageName: String,
    val title: String?,
    val text: String?,
    val conversationTitle: String?,
    val shortcutId: String?,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val looksLikeMessage: Boolean,
    val hasRemoteInput: Boolean,
    val postTime: Long,
)
