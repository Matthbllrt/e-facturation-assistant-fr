package com.glasscontrol.dyson.domain.model

import kotlinx.serialization.Serializable

/** How fresh, and how trustworthy, the values in a [DysonState] are. */
@Serializable
enum class ConnectionStatus { ONLINE, OFFLINE, CONNECTING, UNKNOWN }

/** Coarse air-quality band derived from the individual pollutant readings. */
@Serializable
enum class AirQuality { GOOD, FAIR, POOR, VERY_POOR, UNKNOWN }

/**
 * A protocol-independent snapshot of the machine.
 *
 * Every field is nullable when the machine does not report it, so the UI can bind
 * to this model directly without ever knowing an MQTT field name.
 */
@Serializable
data class DysonState(
    val connection: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val power: Boolean = false,
    /** Null while in auto mode, where the machine picks the speed itself. */
    val fanSpeed: Int? = null,
    val autoMode: Boolean = false,
    val oscillation: Boolean = false,
    /**
     * The raw `oson` token the machine reported.
     *
     * Older Pure Cool machines say OION/OIOF where newer ones say ON/OFF, and a
     * machine only accepts the dialect it speaks, so the token is kept verbatim
     * to be echoed back on the next command.
     */
    val oscillationRaw: String? = null,
    val oscillationAngleLow: Int? = null,
    val oscillationAngleHigh: Int? = null,
    val nightMode: Boolean = false,
    val heating: Boolean = false,
    val heatingActive: Boolean = false,
    /** Target temperature in Celsius, heating machines only. */
    val targetTemperatureC: Float? = null,
    /** True when air is directed to the front. */
    val frontAirflow: Boolean = true,
    /** True while the fan motor is actually spinning. */
    val fanRunning: Boolean = false,
    val continuousMonitoring: Boolean = false,

    val temperatureC: Float? = null,
    val humidity: Int? = null,
    val pm25: Int? = null,
    val pm10: Int? = null,
    val voc: Float? = null,
    val no2: Float? = null,
    val hepaFilterPercent: Int? = null,
    val carbonFilterPercent: Int? = null,
    val filterHours: Int? = null,

    val errorCode: String? = null,
    /** Wall-clock time of the last successful read, for "updated x ago" display. */
    val lastUpdatedEpochMs: Long = 0L,
) {
    val isOnline: Boolean get() = connection == ConnectionStatus.ONLINE

    /**
     * Worst band across the pollutants the machine reports.
     *
     * Thresholds follow the bands Dyson uses in the MyDyson app: PM in µg/m³,
     * VOC and NO2 on the machine's own 0-9 index scale.
     */
    val airQuality: AirQuality
        get() {
            val bands = buildList {
                pm25?.let { add(bandForPm25(it)) }
                pm10?.let { add(bandForPm10(it)) }
                voc?.let { add(bandForIndex(it)) }
                no2?.let { add(bandForIndex(it)) }
            }.filter { it != AirQuality.UNKNOWN }
            if (bands.isEmpty()) return AirQuality.UNKNOWN
            return bands.maxBy { it.ordinal }
        }

    /** The single most representative filter percentage, whichever exists. */
    val filterPercent: Int?
        get() = hepaFilterPercent ?: carbonFilterPercent

    private fun bandForPm25(value: Int) = when {
        value < 0 -> AirQuality.UNKNOWN
        value <= 35 -> AirQuality.GOOD
        value <= 53 -> AirQuality.FAIR
        value <= 70 -> AirQuality.POOR
        else -> AirQuality.VERY_POOR
    }

    private fun bandForPm10(value: Int) = when {
        value < 0 -> AirQuality.UNKNOWN
        value <= 50 -> AirQuality.GOOD
        value <= 75 -> AirQuality.FAIR
        value <= 100 -> AirQuality.POOR
        else -> AirQuality.VERY_POOR
    }

    private fun bandForIndex(value: Float) = when {
        value < 0f -> AirQuality.UNKNOWN
        value <= 3f -> AirQuality.GOOD
        value <= 6f -> AirQuality.FAIR
        value <= 8f -> AirQuality.POOR
        else -> AirQuality.VERY_POOR
    }

    companion object {
        val Offline = DysonState(connection = ConnectionStatus.OFFLINE)
    }
}
