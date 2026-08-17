package com.glasscontrol.dyson.domain.model

import kotlinx.serialization.Serializable

/**
 * What a particular machine can actually do.
 *
 * The UI and the widget only ever draw controls whose capability flag is true, so
 * an unsupported feature disappears instead of failing at command time.
 */
@Serializable
data class DysonCapabilities(
    val power: Boolean = true,
    val fanSpeed: Boolean = true,
    val autoMode: Boolean = true,
    val oscillation: Boolean = true,
    val nightMode: Boolean = true,
    val heating: Boolean = false,
    val targetTemperature: Boolean = false,
    val airflowDirection: Boolean = false,
    val continuousMonitoring: Boolean = true,
    // Sensors
    val temperature: Boolean = true,
    val humidity: Boolean = true,
    val pm25: Boolean = false,
    val pm10: Boolean = false,
    val voc: Boolean = true,
    val no2: Boolean = false,
    val hepaFilter: Boolean = false,
    val carbonFilter: Boolean = false,
    val filterHours: Boolean = false,
    val maxFanSpeed: Int = 10,
) {
    val hasAnySensor: Boolean
        get() = temperature || humidity || pm25 || pm10 || voc || no2

    companion object {
        /**
         * Baseline capabilities implied by the device family.
         *
         * Only the family is required — [refineWithState] then narrows this down
         * using the fields the machine actually reports, which is the only way to
         * be sure about optional hardware such as a carbon filter.
         */
        fun forFamily(family: DysonFamily): DysonCapabilities = when (family) {
            DysonFamily.PURE_COOL_LINK -> DysonCapabilities(
                nightMode = true,
                voc = true,
                filterHours = true,
                airflowDirection = false,
            )

            DysonFamily.HOT_COOL_LINK -> DysonCapabilities(
                heating = true,
                targetTemperature = true,
                voc = true,
                filterHours = true,
                airflowDirection = false,
            )

            DysonFamily.PURE_COOL -> DysonCapabilities(
                pm25 = true,
                pm10 = true,
                voc = true,
                no2 = true,
                hepaFilter = true,
                carbonFilter = true,
                airflowDirection = true,
            )

            DysonFamily.HOT_COOL -> DysonCapabilities(
                heating = true,
                targetTemperature = true,
                pm25 = true,
                pm10 = true,
                voc = true,
                no2 = true,
                hepaFilter = true,
                carbonFilter = true,
                airflowDirection = true,
            )

            DysonFamily.HUMIDIFY_COOL -> DysonCapabilities(
                pm25 = true,
                pm10 = true,
                voc = true,
                no2 = true,
                hepaFilter = true,
                carbonFilter = false,
                airflowDirection = false,
            )

            DysonFamily.BIG_QUIET -> DysonCapabilities(
                pm25 = true,
                pm10 = true,
                voc = true,
                no2 = true,
                hepaFilter = true,
                carbonFilter = true,
                airflowDirection = true,
                oscillation = false,
            )

            DysonFamily.UNSUPPORTED -> DysonCapabilities(
                fanSpeed = false,
                autoMode = false,
                oscillation = false,
                nightMode = false,
                voc = false,
            )
        }
    }
}
