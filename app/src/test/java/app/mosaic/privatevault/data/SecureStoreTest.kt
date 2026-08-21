package app.mosaic.privatevault.data

import app.mosaic.privatevault.core.crypto.Sealer
import app.mosaic.privatevault.data.secure.SecureStore
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the encrypted store against a real AES-GCM sealer.
 *
 * The Android Keystore is not available on the JVM, so the test substitutes an
 * in-process key — the storage format, the atomic write and the erase path are
 * identical, which is what these tests are about.
 */
class SecureStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Same algorithm and framing as KeystoreCrypto, with an in-process key. */
    private class TestSealer(
        private val key: SecretKey = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey(),
    ) : Sealer {
        override fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plain)
            return byteArrayOf(iv.size.toByte()) + iv + body
        }

        override fun open(sealed: ByteArray): ByteArray {
            val ivLength = sealed[0].toInt()
            val iv = sealed.copyOfRange(1, 1 + ivLength)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(sealed.copyOfRange(1 + ivLength, sealed.size))
        }
    }

    private fun store(file: File = folder.newFile("vault.bin").also { it.delete() }, sealer: Sealer = TestSealer()) =
        SecureStore(file, sealer)

    @Test
    fun `a stored value survives a round trip`() {
        val file = File(folder.root, "vault.bin")
        val sealer = TestSealer()
        store(file, sealer).putString("k", "hello")

        assertEquals("hello", store(file, sealer).getString("k"))
    }

    @Test
    fun `the file on disk never contains the plaintext`() {
        val file = File(folder.root, "vault.bin")
        val secret = "camille_r"
        store(file, TestSealer()).putString(SecureStore.KEY_TARGET_HANDLE, secret)

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)

        assertFalse(raw.contains(secret))
        assertFalse(raw.contains(SecureStore.KEY_TARGET_HANDLE))
    }

    @Test
    fun `two seals of the same value differ`() {
        val a = File(folder.root, "a.bin")
        val b = File(folder.root, "b.bin")
        val sealer = TestSealer()
        store(a, sealer).putString("k", "same")
        store(b, sealer).putString("k", "same")

        assertNotEquals(
            a.readBytes().toString(Charsets.ISO_8859_1),
            b.readBytes().toString(Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `getOrCreate generates once and then returns the same value`() {
        val file = File(folder.root, "vault.bin")
        val sealer = TestSealer()
        var generated = 0

        val first = store(file, sealer).getOrCreate("db") {
            generated++
            "passphrase"
        }
        val second = store(file, sealer).getOrCreate("db") {
            generated++
            "other"
        }

        assertEquals("passphrase", first)
        assertEquals("passphrase", second)
        assertEquals(1, generated)
    }

    @Test
    fun `removing a key removes it from disk too`() {
        val file = File(folder.root, "vault.bin")
        val sealer = TestSealer()
        store(file, sealer).apply {
            putString("k", "v")
            remove("k")
        }

        assertNull(store(file, sealer).getString("k"))
    }

    @Test
    fun `destroy unlinks the file`() {
        val file = File(folder.root, "vault.bin")
        val instance = store(file, TestSealer())
        instance.putString("k", "v")

        instance.destroy()

        assertFalse(file.exists())
    }

    @Test
    fun `a vault sealed by a different key reads as empty rather than crashing`() {
        // This is the "restored onto another phone" / "Keystore key gone" case.
        val file = File(folder.root, "vault.bin")
        store(file, TestSealer()).putString("k", "v")

        val withOtherKey = store(file, TestSealer())

        assertNull(withOtherKey.getString("k"))
        assertTrue(withOtherKey.corrupted)
    }

    @Test
    fun `a truncated file does not lose the process`() {
        val file = File(folder.root, "vault.bin")
        val sealer = TestSealer()
        store(file, sealer).putString("k", "v")
        file.writeBytes(file.readBytes().copyOfRange(0, 5))

        assertNull(store(file, sealer).getString("k"))
    }
}
