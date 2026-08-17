package com.glasscontrol.dyson.data.cloud

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** android.util.Base64 is an Android API, so these run under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class DysonCryptoTest {

    @Test
    fun `decrypts a local credential blob into its password hash`() {
        val payload = """{"serial":"NN2-EU-ABC1234A","apPasswordHash":"s3cr3tHash=="}"""
        val encrypted = encryptLikeDyson(payload)

        assertEquals("s3cr3tHash==", DysonCrypto.decryptLocalCredential(encrypted))
    }

    @Test
    fun `derives the MQTT credential from the sticker Wi-Fi password`() {
        // SHA-512 of "test", base64 encoded — the scheme MyDyson clients use.
        val expected = "7iaw3Ur350mqGo7jwQrpkj9hiYB3Lkc/iBml1JQODbJ6wYX4oOHV" +
            "+E+IvIh/1nsUNzLDBMxfqa2Ob1f1ACio/w=="

        assertEquals(expected, DysonCrypto.credentialFromWifiPassword("test"))
    }

    @Test
    fun `parses serial and device type out of the machine's SSID`() {
        assertEquals(
            "NN2-EU-ABC1234A" to "438",
            DysonCrypto.parseWifiSsid("DYSON-NN2-EU-ABC1234A-438"),
        )
        assertEquals(
            "NN2-EU-ABC1234A" to "527K",
            DysonCrypto.parseWifiSsid("DYSON-NN2-EU-ABC1234A-527K"),
        )
        // 455A machines address the 455 topic.
        assertEquals(
            "NN2-EU-ABC1234A" to "455",
            DysonCrypto.parseWifiSsid("DYSON-NN2-EU-ABC1234A-455A"),
        )
    }

    @Test
    fun `rejects an SSID that is not a Dyson`() {
        assertNull(DysonCrypto.parseWifiSsid("Livebox-1234"))
        assertNull(DysonCrypto.parseWifiSsid("DYSON-broken"))
    }

    /** Mirrors how the cloud encrypts LocalCredentials: AES-256-CBC, fixed key, zero IV. */
    private fun encryptLikeDyson(plain: String): String {
        val key = ByteArray(32) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
}
