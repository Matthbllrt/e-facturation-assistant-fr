package app.mosaic.privatevault.data

import app.mosaic.privatevault.data.export.VaultExporter
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import app.mosaic.privatevault.domain.model.VaultMessage
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultExporterTest {

    private val messages = listOf(
        VaultMessage(
            localId = 1L,
            remoteId = "ig-secret-thread-id",
            dedupKey = "r:ig-secret-thread-id",
            timestamp = 1_700_000_000_000L,
            direction = MessageDirection.INCOMING,
            text = "rendez-vous demain",
            mediaKind = MediaKind.TEXT,
            status = DeliveryStatus.RECEIVED,
            source = MessageSource.OFFICIAL_API,
        ),
        VaultMessage(
            localId = 2L,
            dedupKey = "n:OUTGOING:1:abc",
            timestamp = 1_700_000_060_000L,
            direction = MessageDirection.OUTGOING,
            text = null,
            mediaKind = MediaKind.IMAGE,
            status = DeliveryStatus.SENT,
            source = MessageSource.LOCAL_ECHO,
        ),
    )

    @Test
    fun `the export carries the messages`() {
        val json = VaultExporter.serialize(messages)

        assertTrue(json.contains("rendez-vous demain"))
        assertTrue(json.contains("IMAGE"))
    }

    @Test
    fun `the export never carries the Instagram message id`() {
        // An export that names the thread is an export that identifies the person.
        val json = VaultExporter.serialize(messages)

        assertFalse(json.contains("ig-secret-thread-id"))
    }

    @Test
    fun `the exported file is not readable without the passphrase`() {
        val output = ByteArrayOutputStream()

        assertTrue(VaultExporter.export(messages, "correct horse battery".toCharArray(), output))

        val raw = output.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(raw.startsWith("MOSAIC1"))
        assertFalse(raw.contains("rendez-vous demain"))
    }

    @Test
    fun `the exported file decrypts back to the original content`() {
        val passphrase = "correct horse battery"
        val output = ByteArrayOutputStream()
        VaultExporter.export(messages, passphrase.toCharArray(), output)

        val decrypted = decrypt(output.toByteArray(), passphrase)

        assertTrue(decrypted.contains("rendez-vous demain"))
        assertEquals(VaultExporter.serialize(messages).contains("INCOMING"), decrypted.contains("INCOMING"))
    }

    @Test
    fun `two exports of the same data differ`() {
        val a = ByteArrayOutputStream().also { VaultExporter.export(messages, "passphrase-1234".toCharArray(), it) }
        val b = ByteArrayOutputStream().also { VaultExporter.export(messages, "passphrase-1234".toCharArray(), it) }

        assertFalse(a.toByteArray().contentEquals(b.toByteArray()))
    }

    @Test
    fun `a short passphrase is refused`() {
        assertFalse(VaultExporter.isPassphraseAcceptable("court".toCharArray()))
        assertTrue(VaultExporter.isPassphraseAcceptable("assez-longue".toCharArray()))
    }

    /** Reimplements the documented file layout, to prove it is really documented. */
    private fun decrypt(blob: ByteArray, passphrase: String): String {
        var offset = "MOSAIC1".length
        val iterations = ((blob[offset].toInt() and 0xFF) shl 24) or
            ((blob[offset + 1].toInt() and 0xFF) shl 16) or
            ((blob[offset + 2].toInt() and 0xFF) shl 8) or
            (blob[offset + 3].toInt() and 0xFF)
        offset += 4
        val salt = blob.copyOfRange(offset, offset + 16)
        offset += 16
        val iv = blob.copyOfRange(offset, offset + 12)
        offset += 12

        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(blob.copyOfRange(offset, blob.size)), Charsets.UTF_8)
    }
}
