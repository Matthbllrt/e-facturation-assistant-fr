package app.mosaic.privatevault.sync.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Instagram Graph API (Instagram API with Instagram Login).
 *
 * Only the fields Mosaic actually stores are declared; unknown keys are ignored
 * by the JSON configuration, so a Meta-side field addition cannot break a build
 * that is already on someone's phone.
 */
@Serializable
data class ConversationListDto(
    val data: List<ConversationDto> = emptyList(),
    val paging: PagingDto? = null,
)

@Serializable
data class ConversationDto(
    val id: String,
    val participants: ParticipantsDto? = null,
    @SerialName("updated_time") val updatedTime: String? = null,
)

@Serializable
data class ParticipantsDto(val data: List<ParticipantDto> = emptyList())

@Serializable
data class ParticipantDto(
    val id: String,
    val username: String? = null,
    val name: String? = null,
)

@Serializable
data class ConversationMessagesDto(
    val id: String,
    val messages: MessageListDto? = null,
)

@Serializable
data class MessageListDto(
    val data: List<MessageRefDto> = emptyList(),
    val paging: PagingDto? = null,
)

@Serializable
data class MessageRefDto(
    val id: String,
    @SerialName("created_time") val createdTime: String? = null,
)

@Serializable
data class MessageDetailDto(
    val id: String,
    @SerialName("created_time") val createdTime: String? = null,
    val from: ParticipantDto? = null,
    val to: ParticipantsDto? = null,
    val message: String? = null,
    val attachments: AttachmentListDto? = null,
)

@Serializable
data class AttachmentListDto(val data: List<AttachmentDto> = emptyList())

@Serializable
data class AttachmentDto(
    val id: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val name: String? = null,
)

@Serializable
data class PagingDto(val cursors: CursorsDto? = null, val next: String? = null)

@Serializable
data class CursorsDto(val before: String? = null, val after: String? = null)

@Serializable
data class SendMessageRequest(
    val recipient: RecipientDto,
    val message: MessageBodyDto,
)

@Serializable
data class RecipientDto(val id: String)

@Serializable
data class MessageBodyDto(val text: String)

@Serializable
data class SendMessageResponse(
    @SerialName("recipient_id") val recipientId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
)

@Serializable
data class RefreshTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
data class MeDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val username: String? = null,
)

@Serializable
data class GraphErrorEnvelope(val error: GraphErrorDto? = null)

@Serializable
data class GraphErrorDto(
    val message: String? = null,
    val type: String? = null,
    val code: Int? = null,
    @SerialName("error_subcode") val subcode: Int? = null,
)
