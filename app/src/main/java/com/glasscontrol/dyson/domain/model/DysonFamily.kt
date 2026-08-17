package com.glasscontrol.dyson.domain.model

/**
 * Behavioural family of a Dyson device.
 *
 * Dyson exposes many product codes but only a handful of distinct MQTT dialects.
 * The family is what actually decides how a command is encoded — notably the
 * "Link" generation which drives power/auto through a single `fmod` field while
 * later machines use separate `fpwr` and `auto` fields.
 */
enum class DysonFamily {
    PURE_COOL_LINK,
    PURE_COOL,
    HOT_COOL_LINK,
    HOT_COOL,
    HUMIDIFY_COOL,
    BIG_QUIET,
    UNSUPPORTED;

    /** Link-generation machines encode power and auto mode in the same field. */
    val usesLegacyFanMode: Boolean
        get() = this == PURE_COOL_LINK || this == HOT_COOL_LINK

    val supportsHeating: Boolean
        get() = this == HOT_COOL || this == HOT_COOL_LINK

    companion object {
        /**
         * Maps an MQTT root topic level / product type to a family.
         *
         * Newer machines report a type with a regional variant suffix (438K, 527E…)
         * that changes the MQTT topic but not the dialect, so the numeric prefix is
         * what we match on.
         */
        fun fromDeviceType(deviceType: String): DysonFamily {
            val base = deviceType.trimEnd('K', 'E', 'M')
            return when (base) {
                "475", "469" -> PURE_COOL_LINK
                "438", "520" -> PURE_COOL
                "455" -> HOT_COOL_LINK
                "527" -> HOT_COOL
                "358" -> HUMIDIFY_COOL
                "664" -> BIG_QUIET
                else -> UNSUPPORTED
            }
        }

        /** Human label for a device type, used when the cloud gives us no name. */
        fun displayName(deviceType: String): String = when (fromDeviceType(deviceType)) {
            PURE_COOL_LINK -> "Pure Cool Link"
            PURE_COOL -> "Purifier Cool"
            HOT_COOL_LINK -> "Pure Hot+Cool Link"
            HOT_COOL -> "Purifier Hot+Cool"
            HUMIDIFY_COOL -> "Purifier Humidify+Cool"
            BIG_QUIET -> "Purifier Big+Quiet"
            UNSUPPORTED -> "Dyson"
        }
    }
}
