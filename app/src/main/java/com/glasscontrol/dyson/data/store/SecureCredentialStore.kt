package com.glasscontrol.dyson.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.glasscontrol.dyson.core.logW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the local MQTT credential encrypted with a hardware-backed key.
 *
 * The key lives in the Android Keystore and never leaves it: only the ciphertext
 * reaches disk, so a backup or a pulled data directory yields nothing usable.
 */
class SecureCredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    suspend fun putCredential(serial: String, credential: String) = withContext(Dispatchers.IO) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
            val encrypted = cipher.doFinal(credential.toByteArray(Charsets.UTF_8))
            // GCM needs its IV at decrypt time; it is not secret, so it is stored
            // alongside the ciphertext.
            val blob = cipher.iv + encrypted
            prefs.edit()
                .putString(key(serial), Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        }.onFailure { logW("Unable to store credential", it) }
        Unit
    }

    suspend fun getCredential(serial: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key(serial), null) ?: return@withContext null
        runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size <= IV_LENGTH) return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH)
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), spec)
            String(cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrElse {
            // A failure here means the key was invalidated (app data cleared,
            // device restored). Drop the unusable blob so setup can start over.
            logW("Stored credential is no longer readable, clearing it")
            prefs.edit().remove(key(serial)).apply()
            null
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        Unit
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication requirement: the widget must be able to
                // send a command while the phone is locked.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun key(serial: String) = "credential_$serial"

    companion object {
        private const val FILE_NAME = "dyson_secure"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dyson_glass_credential_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
    }
}
