package com.glasscontrol.dyson.data.cloud

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Recovers the local MQTT password from the two sources Dyson offers.
 *
 * Both are device-local secrets: the cloud ships them encrypted with a fixed key
 * that is identical in every MyDyson client, and the sticker path derives them
 * from the Wi-Fi password printed on the machine. Neither is a Dyson account
 * credential, and neither leaves the phone.
 */
object DysonCrypto {

    /** Fixed AES-256 key used by MyDyson clients: bytes 0x01..0x20. */
    private val KEY = ByteArray(32) { (it + 1).toByte() }
    private val IV = ByteArray(16)

    /**
     * Decrypts a `LocalCredentials` / `localBrokerCredentials` blob.
     *
     * @return the `apPasswordHash` value used as MQTT password.
     */
    fun decryptLocalCredential(encrypted: String): String {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        val decoded = Base64.decode(encrypted, Base64.DEFAULT)
        require(decoded.isNotEmpty() && decoded.size % 16 == 0) { "Invalid credential blob" }

        val plain = cipher.doFinal(decoded)
        val json = String(stripPadding(plain), Charsets.UTF_8)
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(json) as JsonObject
        return root["apPasswordHash"]?.jsonPrimitive?.contentOrNull
            ?: error("Credential blob has no apPasswordHash")
    }

    /**
     * Derives the MQTT password from the Wi-Fi password on the machine's sticker.
     *
     * This is the offline path: it needs no Dyson account at all.
     */
    fun credentialFromWifiPassword(wifiPassword: String): String {
        val digest = MessageDigest.getInstance("SHA-512").digest(wifiPassword.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * Parses the serial and device type out of the machine's Wi-Fi SSID,
     * e.g. `DYSON-NN2-EU-ABC1234A-438`.
     */
    fun parseWifiSsid(ssid: String): Pair<String, String>? {
        val match = Regex("^DYSON-([0-9A-Z]{3}-[A-Z]{2}-[0-9A-Z]{6,})-([0-9]{3}[A-Z]?)$")
            .find(ssid.trim().uppercase()) ?: return null
        val deviceType = match.groupValues[2].let { if (it == "455A") "455" else it }
        return match.groupValues[1] to deviceType
    }

    /**
     * Removes the trailing padding from the decrypted blob.
     *
     * The payload uses PKCS#7-style padding, but some firmware pads with 0x00 or
     * 0x08 backspace bytes instead, so anything after the closing brace is dropped.
     */
    private fun stripPadding(plain: ByteArray): ByteArray {
        val end = plain.indexOfLast { it == '}'.code.toByte() }
        return if (end >= 0) plain.copyOfRange(0, end + 1) else plain
    }
}
