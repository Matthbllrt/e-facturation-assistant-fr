package app.mosaic.privatevault.sync.api

import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.data.secure.SecureStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Instagram Graph API access for Mode A (professional accounts).
 *
 * Credentials model, and why it looks like this: exchanging an OAuth code for a
 * token requires the Meta app secret, and a secret shipped inside an APK is not
 * a secret. Mosaic therefore never performs the exchange. The user obtains a
 * long-lived token from their own Meta app and pastes it in; Mosaic stores it
 * sealed by the Keystore and refreshes it against `refresh_access_token`, which
 * is the one token endpoint that needs no secret.
 *
 * Mosaic never asks for, sees, or stores an Instagram password.
 */
class InstagramApiClient(private val store: SecureStore) {

    sealed interface Failure {
        /** No usable network. The caller keeps the local vault as-is. */
        data object Offline : Failure

        /** Token expired, revoked, or the account stopped being professional. */
        data object AuthenticationRequired : Failure

        /** Meta is rate limiting; retry later, do not hammer. */
        data class RateLimited(val retryAfterSeconds: Long?) : Failure
        data class Server(val code: Int) : Failure
        data class Unexpected(val reason: String) : Failure
    }

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data class Error(val failure: Failure) : Result<Nothing>
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val authInterceptor = Interceptor { chain ->
        val token = accessToken()
            ?: return@Interceptor chain.proceed(chain.request())
        // The token travels as a query parameter because that is what the Graph
        // API expects; it is never logged, and OkHttp's logging interceptor is
        // deliberately not installed.
        val url = chain.request().url.newBuilder()
            .setQueryParameter("access_token", token)
            .build()
        chain.proceed(chain.request().newBuilder().url(url).build())
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val service: InstagramService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL.toHttpUrl())
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(InstagramService::class.java)
    }

    fun hasToken(): Boolean = !accessToken().isNullOrBlank()

    fun accessToken(): String? = store.getString(SecureStore.KEY_API_TOKEN)

    fun storeToken(token: String, expiresInSeconds: Long?) {
        store.putString(SecureStore.KEY_API_TOKEN, token.trim())
        expiresInSeconds?.let {
            store.putString(
                SecureStore.KEY_API_TOKEN_EXPIRY,
                (System.currentTimeMillis() + it * 1000L).toString(),
            )
        }
    }

    /** The professional account's own id, used to tell incoming from outgoing. */
    fun selfUserId(): String? = store.getString(SecureStore.KEY_API_USER_ID)

    fun storeSelfUserId(id: String) = store.putString(SecureStore.KEY_API_USER_ID, id)

    fun tokenExpiryMillis(): Long? =
        store.getString(SecureStore.KEY_API_TOKEN_EXPIRY)?.toLongOrNull()

    /** Long-lived tokens last 60 days; renew once past halfway. */
    fun shouldRefresh(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiry = tokenExpiryMillis() ?: return false
        return nowMillis > expiry - REFRESH_MARGIN_MILLIS
    }

    fun clearToken() {
        store.remove(SecureStore.KEY_API_TOKEN)
        store.remove(SecureStore.KEY_API_TOKEN_EXPIRY)
        store.remove(SecureStore.KEY_API_USER_ID)
    }

    suspend fun <T : Any> call(block: suspend InstagramService.() -> Response<T>): Result<T> {
        if (!hasToken()) return Result.Error(Failure.AuthenticationRequired)
        return try {
            val response = service.block()
            if (response.isSuccessful) {
                val body = response.body()
                if (body == null) Result.Error(Failure.Unexpected("empty_body"))
                else Result.Success(body)
            } else {
                Result.Error(classify(response))
            }
        } catch (error: IOException) {
            // Plane mode, captive portal, dead Wi-Fi: not an error the user needs
            // to act on, and never a reason to touch the stored messages.
            SafeLog.d("api.offline")
            Result.Error(Failure.Offline)
        } catch (error: Exception) {
            SafeLog.e("api.call_failed", error)
            Result.Error(Failure.Unexpected(error::class.java.simpleName))
        }
    }

    private fun classify(response: Response<*>): Failure {
        val code = response.code()
        val graphCode = runCatching {
            response.errorBody()?.string()?.let { json.decodeFromString<GraphErrorEnvelope>(it) }
        }.getOrNull()?.error?.code

        return when {
            // 190 = access token problem; 102 = session invalid; 10/200 = missing permission.
            code == 401 || graphCode == 190 || graphCode == 102 -> Failure.AuthenticationRequired
            code == 403 && (graphCode == 10 || graphCode == 200) -> Failure.AuthenticationRequired
            code == 429 || graphCode == 4 || graphCode == 32 || graphCode == 613 ->
                Failure.RateLimited(response.headers()["Retry-After"]?.toLongOrNull())

            code >= 500 -> Failure.Server(code)
            else -> Failure.Unexpected("http_$code")
        }
    }

    suspend fun refreshTokenIfNeeded(): Boolean {
        if (!hasToken() || !shouldRefresh()) return false
        return when (val result = call { refreshToken() }) {
            is Result.Success -> {
                storeToken(result.value.accessToken, result.value.expiresIn)
                SafeLog.i("api.token_refreshed")
                true
            }

            is Result.Error -> {
                SafeLog.w("api.token_refresh_failed")
                false
            }
        }
    }

    companion object {
        /** Graph API host for Instagram-login tokens. */
        const val BASE_URL = "https://graph.instagram.com/v23.0/"

        /** Refresh once fewer than 10 days remain on a 60-day token. */
        private const val REFRESH_MARGIN_MILLIS = 10L * 24 * 60 * 60 * 1000
    }
}
