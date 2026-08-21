package app.mosaic.privatevault.data.export

import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.domain.model.VaultMessage
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Optional encrypted export.
 *
 * The export leaves the device's Keystore behind, so it is protected by a
 * passphrase the user chooses and nothing else: PBKDF2-SHA256 to a 256-bit key,
 * AES-GCM for the payload. A weak passphrase means a weak export, which is why
 * the UI says so out loud and why there is no "export unencrypted" option.
 *
 * File layout: `MOSAIC1` | iterations (4B BE) | salt (16B) | iv (12B) | ciphertext.
 */
object VaultExporter {

    private const val MAGIC = "MOSAIC1"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    const val MIN_PASSPHRASE_LENGTH = 10

    fun export(
        messages: List<VaultMessage>,
        passphrase: CharArray,
        output: OutputStream,
    ): Boolean = try {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, ITERATIONS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))

        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(intToBytes(ITERATIONS))
        output.write(salt)
        output.write(iv)
        output.write(cipher.doFinal(serialize(messages).toByteArray(Charsets.UTF_8)))
        output.flush()
        true
    } catch (error: Exception) {
        SafeLog.e("export.failed", error)
        false
    }

    @Serializable
    data class ExportedMessage(
        val timestamp: Long,
        val direction: String,
        val text: String?,
        val media: String,
        val status: String,
        val source: String,
    )

    @Serializable
    data class ExportEnvelope(
        val format: String,
        val exportedAt: Long,
        val messages: List<ExportedMessage>,
    )

    private val json = Json { prettyPrint = false }

    fun serialize(messages: List<VaultMessage>): String = json.encodeToString(
        ExportEnvelope.serializer(),
        ExportEnvelope(
            format = MAGIC,
            exportedAt = System.currentTimeMillis(),
            messages = messages.map { message ->
                ExportedMessage(
                    timestamp = message.timestamp,
                    direction = message.direction.name,
                    text = message.text,
                    media = message.mediaKind.name,
                    status = message.status.name,
                    source = message.source.name,
                    // The Instagram message id is deliberately absent: an export
                    // that names the thread is an export that identifies the person.
                )
            },
        ),
    )

    fun isPassphraseAcceptable(passphrase: CharArray): Boolean =
        passphrase.size >= MIN_PASSPHRASE_LENGTH

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
