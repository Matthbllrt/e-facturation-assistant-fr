package app.mosaic.privatevault.data.secure

import app.mosaic.privatevault.core.crypto.Sealer
import app.mosaic.privatevault.core.log.SafeLog
import java.io.File
import java.security.SecureRandom
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The small encrypted key/value store for everything too sensitive for
 * DataStore: the database passphrase, the real Instagram handle, the API
 * access token, the PIN verifier.
 *
 * The whole map is sealed as one blob, so an observer cannot even learn which
 * keys are present. It is written atomically because a half-written vault would
 * lock the user out of their own messages.
 */
class SecureStore(
    private val file: File,
    private val sealer: Sealer,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    fun getString(key: String): String? = synchronized(lock) { load()[key] }

    fun putString(key: String, value: String) = synchronized(lock) {
        val map = load()
        map[key] = value
        persist(map)
    }

    fun remove(key: String) = synchronized(lock) {
        val map = load()
        if (map.remove(key) != null) persist(map)
    }

    fun contains(key: String): Boolean = getString(key) != null

    /** Returns the value for [key], creating it from [generate] on first use. */
    fun getOrCreate(key: String, generate: () -> String): String = synchronized(lock) {
        val map = load()
        map[key] ?: generate().also {
            map[key] = it
            persist(map)
        }
    }

    /**
     * Best-effort secure erase: the payload is overwritten with random bytes of
     * the same length before the file is unlinked, so a filesystem that does not
     * reuse the block immediately still yields nothing.
     */
    fun destroy() = synchronized(lock) {
        cache = null
        if (file.exists()) {
            runCatching {
                val length = file.length().toInt().coerceAtLeast(1)
                val noise = ByteArray(length).also { SecureRandom().nextBytes(it) }
                file.writeBytes(noise)
            }.onFailure { SafeLog.w("secure_store.overwrite_failed", it) }
            if (!file.delete()) SafeLog.w("secure_store.delete_failed")
        }
    }

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val map = mutableMapOf<String, String>()
        if (file.exists()) {
            try {
                val decoded = json.decodeFromString(
                    MapSerializer(String.serializer(), String.serializer()),
                    String(sealer.open(file.readBytes()), Charsets.UTF_8),
                )
                map.putAll(decoded)
            } catch (error: Exception) {
                // Unreadable vault: the Keystore key is gone (app data cleared,
                // device restored) or the file is corrupt. Starting empty is the
                // only recoverable answer, and it is reported to the user as
                // "vault must be re-created" rather than swallowed.
                SafeLog.e("secure_store.unreadable", error)
                corrupted = true
            }
        }
        cache = map
        return map
    }

    /** True when the last load found an existing but undecryptable vault. */
    @Volatile
    var corrupted: Boolean = false
        private set

    private fun persist(map: Map<String, String>) {
        val encoded = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            map,
        )
        val sealed = sealer.seal(encoded.toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            file.writeBytes(sealed)
            temp.delete()
        }
        cache = map.toMutableMap()
    }

    companion object {
        const val KEY_DATABASE_PASSPHRASE = "db.passphrase"
        const val KEY_TARGET_HANDLE = "target.handle"
        const val KEY_TARGET_DISPLAY_NAME = "target.display_name"
        const val KEY_TARGET_THREAD_ID = "target.thread_id"
        const val KEY_PIN_VERIFIER = "lock.pin"
        const val KEY_API_TOKEN = "api.access_token"
        const val KEY_API_TOKEN_EXPIRY = "api.access_token_expiry"
        const val KEY_API_USER_ID = "api.user_id"
    }
}
