package com.glasscontrol.dyson.data.cloud

import com.glasscontrol.dyson.core.DysonError
import com.glasscontrol.dyson.core.logW
import com.glasscontrol.dyson.domain.model.DysonDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Opaque handle for an in-flight OTP login. */
data class LoginChallenge(val email: String, val challengeId: String, val region: String)

/** The bearer token returned once the OTP is verified. */
data class DysonAccountToken(val account: String, val token: String)

/**
 * Talks to the MyDyson account API.
 *
 * The API is only used to enumerate the user's machines and hand back their
 * encrypted local credentials. Once onboarding is done the app never calls it
 * again — all control is local.
 */
class DysonCloudApi(
    private val client: OkHttpClient = defaultClient(),
    private val china: Boolean = false,
) {
    private val host = if (china) HOST_CN else HOST
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Starts an email OTP login.
     *
     * Dyson requires a provisioning call first: it whitelists the caller's address
     * before any registration endpoint will answer.
     */
    suspend fun startLogin(email: String, region: String): Result<LoginChallenge> =
        withContext(Dispatchers.IO) {
            runCatching {
                provision()

                val statusBody = post(
                    path = "/v3/userregistration/email/userstatus",
                    query = mapOf("country" to region),
                    body = buildJsonObject { put("email", email) },
                )
                val accountStatus = statusBody["accountStatus"]?.jsonPrimitive?.contentOrNull
                if (accountStatus != null && !accountStatus.equals("ACTIVE", ignoreCase = true)) {
                    throw DysonError.Cloud("Ce compte MyDyson n'est pas actif ($accountStatus)")
                }

                val authBody = post(
                    path = "/v3/userregistration/email/auth",
                    query = mapOf("country" to region, "culture" to "fr-FR"),
                    body = buildJsonObject { put("email", email) },
                )
                val challengeId = authBody["challengeId"]?.jsonPrimitive?.contentOrNull
                    ?: throw DysonError.Cloud("Réponse inattendue du service Dyson")

                LoginChallenge(email, challengeId, region)
            }.mapCloudFailure()
        }

    /**
     * Completes the login with the emailed code and the account password.
     *
     * The password is used for this single call and never stored.
     */
    suspend fun verifyLogin(
        challenge: LoginChallenge,
        otpCode: String,
        password: String,
    ): Result<DysonAccountToken> = withContext(Dispatchers.IO) {
        runCatching {
            val body = post(
                path = "/v3/userregistration/email/verify",
                body = buildJsonObject {
                    put("email", challenge.email)
                    put("password", password)
                    put("challengeId", challenge.challengeId)
                    put("otpCode", otpCode)
                },
            )
            val token = body["token"]?.jsonPrimitive?.contentOrNull
                ?: throw DysonError.Cloud("Code ou mot de passe refusé")
            DysonAccountToken(
                account = body["account"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                token = token,
            )
        }.mapCloudFailure()
    }

    /**
     * Lists the machines on the account, with their local credentials decrypted.
     *
     * The modern `/v3/manifest` is tried first because it reports the exact MQTT
     * root topic; the legacy manifest is the fallback for older accounts.
     */
    suspend fun listDevices(token: DysonAccountToken): Result<List<DysonDevice>> =
        withContext(Dispatchers.IO) {
            runCatching {
                provision()
                val modern = runCatching { getArray("/v3/manifest", token) }.getOrNull()
                val devices = if (modern != null) {
                    modern.mapNotNull { parseModernDevice(it as? JsonObject ?: return@mapNotNull null) }
                } else {
                    getArray("/v2/provisioningservice/manifest", token)
                        .mapNotNull { parseLegacyDevice(it as? JsonObject ?: return@mapNotNull null) }
                }
                devices
            }.mapCloudFailure()
        }

    /** v3 shape: connected machines nest their MQTT config under connectedConfiguration. */
    private fun parseModernDevice(raw: JsonObject): DysonDevice? {
        val serial = raw.string("serialNumber") ?: return null
        val connected = raw["connectedConfiguration"] as? JsonObject
            ?: return null // e.g. Lightcycle lamps: no local broker, not controllable here.
        val mqtt = connected["mqtt"] as? JsonObject ?: return null
        val encrypted = mqtt.string("localBrokerCredentials") ?: return null
        val topic = mqtt.string("mqttRootTopicLevel")
            ?: raw.string("type")
            ?: return null

        val credential = runCatching { DysonCrypto.decryptLocalCredential(encrypted) }
            .getOrElse {
                logW("Unable to decrypt local credential for a device")
                return null
            }

        return DysonDevice(
            serial = serial,
            name = raw.string("name") ?: serial,
            deviceType = topic,
            credential = credential,
            model = raw.string("model"),
            firmware = (connected["firmware"] as? JsonObject)?.string("version"),
        )
    }

    /** v2 shape: flat object with PascalCase keys and a separate variant field. */
    private fun parseLegacyDevice(raw: JsonObject): DysonDevice? {
        val serial = raw.string("Serial") ?: return null
        val encrypted = raw.string("LocalCredentials") ?: return null
        val productType = raw.string("ProductType") ?: return null
        val version = raw.string("Version").orEmpty()
        val variant = raw.string("variant")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: variantFromFirmware(productType, version)

        val credential = runCatching { DysonCrypto.decryptLocalCredential(encrypted) }
            .getOrElse { return null }

        // Machines in the 438/527/358 lines address different MQTT topics per variant.
        val deviceType = if (variant != null && productType in VARIANT_TYPES) {
            productType + variant
        } else {
            productType
        }

        return DysonDevice(
            serial = serial,
            name = raw.string("Name") ?: serial,
            deviceType = deviceType,
            credential = credential,
            firmware = version.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Recovers the regional variant from the firmware string when the manifest
     * omits it, e.g. `438MPF.00.01.003` means product 438, variant M.
     */
    private fun variantFromFirmware(productType: String, version: String): String? {
        if (productType !in VARIANT_TYPES || version.length < 4) return null
        if (version.startsWith("ECG2") && productType == "358") return "E"
        if (!version.startsWith(productType)) return null
        return version[3].toString().takeIf { it in listOf("E", "K", "M") }
    }

    /** Unlocks the API for this IP; the returned version string is not needed. */
    private fun provision() {
        val request = Request.Builder()
            .url("$host/v1/provisioningservice/application/Android/version")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DysonError.Cloud("Service Dyson indisponible (${response.code})")
            }
        }
    }

    private fun post(
        path: String,
        body: JsonObject,
        query: Map<String, String> = emptyMap(),
        token: DysonAccountToken? = null,
    ): JsonObject {
        val response = execute(path, query, token) { builder ->
            builder.post(body.toString().toRequestBody(JSON_MEDIA))
        }
        return json.parseToJsonElement(response) as? JsonObject
            ?: throw DysonError.Cloud("Réponse inattendue du service Dyson")
    }

    private fun getArray(path: String, token: DysonAccountToken): JsonArray {
        val response = execute(path, emptyMap(), token) { it.get() }
        return json.parseToJsonElement(response) as? JsonArray
            ?: throw DysonError.Cloud("Réponse inattendue du service Dyson")
    }

    private fun execute(
        path: String,
        query: Map<String, String>,
        token: DysonAccountToken?,
        configure: (Request.Builder) -> Request.Builder,
    ): String {
        val url = (host + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        token?.let { builder.header("Authorization", "Bearer ${it.token}") }

        client.newCall(configure(builder).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 429 ->
                    throw DysonError.Cloud("Trop de demandes de code. Réessayez dans quelques minutes.")

                response.code == 400 ->
                    throw DysonError.Cloud("Code de vérification ou mot de passe incorrect")

                response.code in 401..403 ->
                    throw DysonError.Cloud("Session Dyson expirée, reconnectez-vous")

                response.code >= 500 ->
                    throw DysonError.Cloud("Service Dyson momentanément indisponible")

                !response.isSuccessful ->
                    throw DysonError.Cloud("Erreur Dyson (${response.code})")
            }
            return body
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Network faults become a domain error; cloud errors pass through untouched. */
    private fun <T> Result<T>.mapCloudFailure(): Result<T> = recoverCatching { error ->
        throw when (error) {
            is DysonError -> error
            is IOException -> DysonError.Cloud("Connexion au service Dyson impossible", error)
            else -> DysonError.Cloud("Erreur inattendue", error)
        }
    }

    companion object {
        private const val HOST = "https://appapi.cp.dyson.com"
        private const val HOST_CN = "https://appapi.cp.dyson.cn"
        private const val USER_AGENT = "android client"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val VARIANT_TYPES = setOf("438", "527", "358")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
