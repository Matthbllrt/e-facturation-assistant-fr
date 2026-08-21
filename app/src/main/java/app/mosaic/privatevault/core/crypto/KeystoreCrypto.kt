package app.mosaic.privatevault.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM sealing backed by the Android Keystore.
 *
 * The master key never leaves the TEE / StrongBox, so the encrypted blobs on
 * disk are worthless on any other device and worthless after a factory reset.
 *
 * Deliberate design note — the master key is *not* bound to user
 * authentication. Mosaic's notification capture path runs while the phone is
 * locked (that is when messages actually arrive), and an auth-bound key would
 * make it fail exactly then, silently losing messages. Authentication is
 * therefore enforced at the UI boundary (see `security/AppLock`), while the
 * key protects data at rest against offline extraction. `setUnlockedDeviceRequired`
 * is off for the same reason.
 */
class KeystoreCrypto(private val alias: String = DEFAULT_ALIAS) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Seals [plain] and returns `iv || ciphertext||tag`. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return ByteArray(1 + iv.size + body.size).also { out ->
            out[0] = iv.size.toByte()
            iv.copyInto(out, 1)
            body.copyInto(out, 1 + iv.size)
        }
    }

    /** Reverses [seal]. Throws if the key is gone or the blob was tampered with. */
    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 2) { "sealed blob too short" }
        val ivLength = sealed[0].toInt()
        require(ivLength in 12..16 && sealed.size > 1 + ivLength) { "malformed sealed blob" }
        val iv = sealed.copyOfRange(1, 1 + ivLength)
        val body = sealed.copyOfRange(1 + ivLength, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    fun keyExists(): Boolean = keyStore.containsAlias(alias)

    /**
     * Destroys the master key. Every blob sealed with it becomes permanently
     * unreadable — this is the cryptographic half of "erase everything".
     */
    fun destroyKey() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun obtainKey(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        // StrongBox is a hardware upgrade where it exists (recent Galaxy devices
        // have it) and simply absent elsewhere; never a hard requirement.
        try {
            generator.init(spec(strongBox = true))
            return generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            // Fall through to the TEE-backed key.
        }
        generator.init(spec(strongBox = false))
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "mosaic.master.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
