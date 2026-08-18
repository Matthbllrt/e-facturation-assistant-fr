package com.glasscontrol.dyson.domain.model

import kotlinx.serialization.Serializable

/**
 * Everything needed to reach and identify one machine.
 *
 * The [credential] is the decrypted local MQTT password. It is only ever held in
 * memory or inside the Keystore-encrypted store — never in DataStore in clear.
 */
@Serializable
data class DysonDevice(
    val serial: String,
    val name: String,
    /** MQTT root topic level, e.g. "438K". */
    val deviceType: String,
    val credential: String,
    /** Last known local address; null until discovery or manual entry resolves one. */
    val host: String? = null,
    val model: String? = null,
    val firmware: String? = null,
) {
    val family: DysonFamily get() = DysonFamily.fromDeviceType(deviceType)

    val commandTopic: String get() = "$deviceType/$serial/command"

    val statusTopic: String get() = "$deviceType/$serial/status/current"

    val displayModel: String get() = model ?: DysonFamily.displayName(deviceType)

    /**
     * Compact model badge for the widget, e.g. "TP07".
     *
     * The account reports the real code for most machines; otherwise the range's
     * representative code stands in.
     */
    val shortModel: String
        get() = model?.takeIf { it.isNotBlank() && it.length <= 6 }
            ?: DysonFamily.shortCode(deviceType)

    /** Identity without the secret, safe to persist in plain DataStore. */
    fun withoutCredential(): DysonDevice = copy(credential = "")
}
