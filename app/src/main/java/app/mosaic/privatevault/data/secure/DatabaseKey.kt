package app.mosaic.privatevault.data.secure

import java.security.SecureRandom
import java.util.Base64

/**
 * Supplies the SQLCipher passphrase.
 *
 * The passphrase is 32 random bytes generated once on this device and kept only
 * inside [SecureStore], i.e. sealed by the Android Keystore. Nothing derived
 * from the user's PIN is involved, so changing the PIN never risks the
 * database, and the passphrase is not guessable from anything the user knows.
 */
class DatabaseKey(private val store: SecureStore) {

    fun passphrase(): ByteArray {
        val encoded = store.getOrCreate(SecureStore.KEY_DATABASE_PASSPHRASE) {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            Base64.getEncoder().encodeToString(raw)
        }
        return Base64.getDecoder().decode(encoded)
    }

    fun exists(): Boolean = store.contains(SecureStore.KEY_DATABASE_PASSPHRASE)

    fun destroy() = store.remove(SecureStore.KEY_DATABASE_PASSPHRASE)
}
