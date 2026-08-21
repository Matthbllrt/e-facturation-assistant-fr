package app.mosaic.privatevault.sync.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The subset of the Instagram Graph API that Mosaic uses.
 *
 * Note what is absent: there is no delete-conversation call, because Meta
 * documents none. See docs/INSTAGRAM.md.
 */
interface InstagramService {

    @GET("me")
    suspend fun me(
        @Query("fields") fields: String = "id,user_id,username",
    ): Response<MeDto>

    @GET("me/conversations")
    suspend fun conversations(
        @Query("fields") fields: String = "id,participants,updated_time",
        @Query("limit") limit: Int = 50,
    ): Response<ConversationListDto>

    @GET("{conversationId}")
    suspend fun conversationMessages(
        @Path("conversationId") conversationId: String,
        @Query("fields") fields: String = "messages{id,created_time}",
    ): Response<ConversationMessagesDto>

    @GET("{messageId}")
    suspend fun message(
        @Path("messageId") messageId: String,
        @Query("fields") fields: String = "id,created_time,from,to,message,attachments",
    ): Response<MessageDetailDto>

    @POST("me/messages")
    suspend fun send(@Body body: SendMessageRequest): Response<SendMessageResponse>

    /**
     * Long-lived token renewal. Notably this endpoint takes no app secret,
     * which is what makes a secretless client possible at all.
     */
    @GET("refresh_access_token")
    suspend fun refreshToken(
        @Query("grant_type") grantType: String = "ig_refresh_token",
    ): Response<RefreshTokenResponse>
}
