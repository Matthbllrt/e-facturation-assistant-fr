package app.mosaic.privatevault.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Backup PIN verification.
 *
 * A PIN has very little entropy, so the only defence worth having is making
 * each guess expensive and making the stored form useless elsewhere: PBKDF2
 * with a per-install random salt and a high iteration count, compared in
 * constant time. Rate limiting lives in `security/AppLock`.
 */
object PinHasher {

    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** Serialized as `iterations:base64(salt):base64(hash)`. */
    fun hash(pin: CharArray, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(pin, salt, iterations)
        return buildString {
            append(iterations)
            append(':')
            append(encode(salt))
            append(':')
            append(encode(derived))
        }
    }

    fun verify(pin: CharArray, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = decode(parts[1]) ?: return false
        val expected = decode(parts[2]) ?: return false
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    fun isAcceptable(pin: CharArray): Boolean {
        if (pin.size < MIN_LENGTH || pin.size > MAX_LENGTH) return false
        if (pin.all { !it.isDigit() }) return false
        // Reject the two patterns that make a PIN worthless: all-same and a
        // straight run in either direction.
        if (pin.all { it == pin[0] }) return false
        val ascending = pin.indices.all { pin[it].code == pin[0].code + it }
        val descending = pin.indices.all { pin[it].code == pin[0].code - it }
        return !(ascending || descending)
    }

    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
