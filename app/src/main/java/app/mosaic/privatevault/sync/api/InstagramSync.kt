package app.mosaic.privatevault.sync.api

import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.data.repo.ConversationRepository
import app.mosaic.privatevault.data.repo.MessageCapture
import app.mosaic.privatevault.data.repo.TargetRepository
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pulls the selected conversation over the official API and files it in the
 * vault.
 *
 * Two documented limits shape this and are surfaced to the user rather than
 * hidden: only the 20 most recent messages of a thread can be read back, and
 * only conversations the professional account is party to exist at all. Mosaic
 * consequently accumulates history over time rather than importing the past.
 */
class InstagramSync(
    private val client: InstagramApiClient,
    private val target: TargetRepository,
) {

    sealed interface Result {
        data class Synced(val stored: Int, val seen: Int) : Result
        data object NoConversation : Result
        data class Failed(val failure: InstagramApiClient.Failure) : Result
    }

    suspend fun sync(repository: ConversationRepository): Result {
        client.refreshTokenIfNeeded()

        val identity = target.current() ?: return Result.NoConversation
        val selfId = client.selfUserId() ?: run {
            when (val me = client.call { me() }) {
                is InstagramApiClient.Result.Success ->
                    (me.value.userId ?: me.value.id).also(client::storeSelfUserId)

                is InstagramApiClient.Result.Error -> return Result.Failed(me.failure)
            }
        }

        val conversations = when (val result = client.call { conversations() }) {
            is InstagramApiClient.Result.Success -> result.value.data
            is InstagramApiClient.Result.Error -> return Result.Failed(result.failure)
        }

        val thread = conversations.firstOrNull { conversation ->
            val participants = conversation.participants?.data.orEmpty()
            when {
                identity.threadKey != null && conversation.id == identity.threadKey -> true
                identity.handle != null -> participants.any {
                    it.username.equals(identity.handle, ignoreCase = true)
                }

                else -> participants.any { it.name.equals(identity.displayName, ignoreCase = true) }
            }
        } ?: return Result.NoConversation

        val refs = when (val result = client.call { conversationMessages(thread.id) }) {
            is InstagramApiClient.Result.Success -> result.value.messages?.data.orEmpty()
            is InstagramApiClient.Result.Error -> return Result.Failed(result.failure)
        }

        var stored = 0
        for (ref in refs) {
            val detail = when (val result = client.call { message(ref.id) }) {
                is InstagramApiClient.Result.Success -> result.value
                is InstagramApiClient.Result.Error -> {
                    // A single unreadable message (older than the 20-message
                    // window, or media that expired) must not abort the sync.
                    SafeLog.d("api.message_unavailable")
                    continue
                }
            }

            val outcome = repository.record(detail.toCapture(selfId))
            if (outcome is ConversationRepository.Outcome.Stored) stored++
        }

        SafeLog.i("api.sync_complete stored=$stored seen=${refs.size}")
        return Result.Synced(stored = stored, seen = refs.size)
    }

    sealed interface SendResult {
        data class Sent(val remoteId: String?) : SendResult

        /**
         * Meta's 24-hour rule: a business may only reply within 24 h of the
         * user's last message. Outside that window the send is refused, and the
         * UI offers the Instagram fallback instead of failing silently.
         */
        data object OutsideMessagingWindow : SendResult
        data class Failed(val failure: InstagramApiClient.Failure) : SendResult
    }

    /** Replies over the official Send API. Mode A only. */
    suspend fun send(text: String): SendResult {
        val identity = target.current() ?: return SendResult.Failed(
            InstagramApiClient.Failure.Unexpected("no_target"),
        )
        val selfId = client.selfUserId()

        val recipientId = when (val result = client.call { conversations() }) {
            is InstagramApiClient.Result.Success -> result.value.data
                .asSequence()
                .flatMap { it.participants?.data.orEmpty().asSequence() }
                .firstOrNull { participant ->
                    participant.id != selfId && (
                        participant.username.equals(identity.handle, ignoreCase = true) ||
                            participant.name.equals(identity.displayName, ignoreCase = true)
                        )
                }
                ?.id

            is InstagramApiClient.Result.Error -> return SendResult.Failed(result.failure)
        } ?: return SendResult.Failed(InstagramApiClient.Failure.Unexpected("recipient_not_found"))

        val request = SendMessageRequest(RecipientDto(recipientId), MessageBodyDto(text))
        return when (val result = client.call { send(request) }) {
            is InstagramApiClient.Result.Success -> SendResult.Sent(result.value.messageId)
            is InstagramApiClient.Result.Error -> {
                val failure = result.failure
                if (failure is InstagramApiClient.Failure.Unexpected &&
                    failure.reason == "http_400"
                ) {
                    SendResult.OutsideMessagingWindow
                } else {
                    SendResult.Failed(failure)
                }
            }
        }
    }

    private fun MessageDetailDto.toCapture(selfId: String?): MessageCapture {
        val fromSelf = selfId != null && from?.id == selfId
        val attachment = attachments?.data?.firstOrNull()
        return MessageCapture(
            remoteId = id,
            timestamp = parseTimestamp(createdTime),
            direction = if (fromSelf) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            text = message?.takeIf { it.isNotBlank() },
            mediaKind = when {
                attachment == null -> MediaKind.TEXT
                attachment.mimeType?.startsWith("image/") == true -> MediaKind.IMAGE
                attachment.mimeType?.startsWith("video/") == true -> MediaKind.VIDEO
                attachment.mimeType?.startsWith("audio/") == true -> MediaKind.AUDIO
                else -> MediaKind.UNAVAILABLE
            },
            // Attachment URLs from Meta are short-lived signed links; storing one
            // would give the false impression the media is kept. Only the fact
            // that an attachment existed is recorded.
            mediaRef = null,
            status = if (fromSelf) DeliveryStatus.SENT else DeliveryStatus.RECEIVED,
            source = MessageSource.OFFICIAL_API,
        )
    }

    /** Graph timestamps are ISO 8601 with a numeric offset. */
    private fun parseTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            SimpleDateFormat(ISO_8601, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(raw)?.time
        }.getOrNull() ?: System.currentTimeMillis()
    }

    private companion object {
        const val ISO_8601 = "yyyy-MM-dd'T'HH:mm:ssZ"
    }
}
