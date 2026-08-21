package app.mosaic.privatevault.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * Detects "the set of enrolled fingerprints/faces changed since Mosaic was set
 * up" — the classic attack where someone with the unlock code enrols their own
 * biometric and walks straight past the biometric prompt.
 *
 * It works by holding a key that Android itself invalidates on enrolment
 * change. The key guards nothing; only its liveness is meaningful, which is why
 * losing it costs the user a PIN entry rather than their messages.
 */
class BiometricEnrollmentGuard(private val alias: String = ALIAS) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    enum class State {
        /** No enrolment marker yet: first run, or biometrics unavailable. */
        NOT_ENROLLED,

        /** Marker intact — the biometric set is the one Mosaic was set up with. */
        VALID,

        /** Biometrics were added or removed. Force the PIN and re-arm. */
        INVALIDATED,
    }

    fun state(): State {
        if (!keyStore.containsAlias(alias)) return State.NOT_ENROLLED
        return try {
            val key = (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
            State.VALID
        } catch (_: KeyPermanentlyInvalidatedException) {
            State.INVALIDATED
        } catch (_: Exception) {
            // An unreadable marker is treated as suspicious, not as fine.
            State.INVALIDATED
        }
    }

    /** (Re)creates the marker. Call after a successful full authentication. */
    fun arm(): Boolean = try {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        generator.generateKey()
        true
    } catch (_: Exception) {
        // No secure lock screen or no biometric hardware: nothing to guard.
        false
    }

    fun clear() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val ALIAS = "mosaic.biometric.marker.v1"
    }
}
