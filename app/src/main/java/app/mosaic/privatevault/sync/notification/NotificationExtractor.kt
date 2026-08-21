package app.mosaic.privatevault.sync.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.notify.NotificationCandidate

/**
 * Turns a [StatusBarNotification] into the plain-data view the matcher works
 * with, and pulls out the individual messages when Instagram uses
 * MessagingStyle (which it does for direct messages).
 */
object NotificationExtractor {

    data class ExtractedMessage(
        val text: String?,
        val direction: MessageDirection,
        val timestamp: Long,
        val mediaKind: MediaKind,
    )

    fun toCandidate(sbn: StatusBarNotification): NotificationCandidate {
        val notification = sbn.notification
        val extras = notification.extras
        val style = extras.getString(Notification.EXTRA_TEMPLATE)

        return NotificationCandidate(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString(),
            shortcutId = conversationKey(sbn),
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            looksLikeMessage = notification.category == Notification.CATEGORY_MESSAGE ||
                style == MESSAGING_STYLE,
            hasRemoteInput = findReplyAction(notification) != null,
            postTime = sbn.postTime,
        )
    }

    /**
     * A stable per-conversation identifier.
     *
     * `shortcutId` is what Android's conversation APIs use and what Instagram
     * sets for bubbles; the notification tag is the fallback. Both survive the
     * person renaming themselves, which is exactly why they are preferred over
     * the display name.
     */
    fun conversationKey(sbn: StatusBarNotification): String? {
        return sbn.notification.shortcutId?.takeIf { it.isNotBlank() }
            ?: sbn.tag?.takeIf { it.isNotBlank() }
    }

    /**
     * The messages carried by a MessagingStyle notification, oldest first.
     *
     * Falls back to the flat EXTRA_TEXT when Instagram does not use the style,
     * because a captured message with a rough timestamp beats no message.
     */
    fun extractMessages(sbn: StatusBarNotification): List<ExtractedMessage> {
        val notification = sbn.notification
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)

        if (style != null && style.messages.isNotEmpty()) {
            return style.messages.map { message ->
                ExtractedMessage(
                    text = message.text?.toString(),
                    // MessagingStyle marks the device owner's own messages with
                    // a null person; everything else came from the other side.
                    direction = if (message.person == null) {
                        MessageDirection.OUTGOING
                    } else {
                        MessageDirection.INCOMING
                    },
                    timestamp = message.timestamp.takeIf { it > 0L } ?: sbn.postTime,
                    mediaKind = mediaKindFor(message.dataMimeType, message.text?.toString()),
                )
            }
        }

        val flat = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (flat.isNullOrBlank()) return emptyList()
        return listOf(
            ExtractedMessage(
                text = flat,
                direction = MessageDirection.INCOMING,
                timestamp = sbn.postTime,
                mediaKind = MediaKind.TEXT,
            ),
        )
    }

    private fun mediaKindFor(mimeType: String?, text: String?): MediaKind = when {
        mimeType == null -> if (text.isNullOrBlank()) MediaKind.UNAVAILABLE else MediaKind.TEXT
        mimeType.startsWith("image/") -> MediaKind.IMAGE
        mimeType.startsWith("video/") -> MediaKind.VIDEO
        mimeType.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.UNAVAILABLE
    }

    /** The system "reply" affordance, when Instagram offers one. */
    fun findReplyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

    private const val MESSAGING_STYLE = "android.app.Notification\$MessagingStyle"
}
