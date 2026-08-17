package com.glasscontrol.dyson.data.local

import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns raw Dyson MQTT payloads into a [DysonState].
 *
 * Two message shapes matter: `CURRENT-STATE`/`STATE-CHANGE` carry a
 * `product-state` object, and `ENVIRONMENTAL-CURRENT-SENSOR-DATA` carries a
 * `data` object with the sensor readings. A machine sends them independently, so
 * the parser merges each one into the previous state rather than replacing it.
 */
class DysonStateParser(private val family: DysonFamily) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Result of feeding one payload in. */
    data class ParseResult(val state: DysonState, val handled: Boolean)

    fun parse(payload: String, previous: DysonState): ParseResult =
        runCatching { parseObject(json.parseToJsonElement(payload) as JsonObject, previous) }
            .getOrElse { ParseResult(previous, handled = false) }

    fun parseObject(root: JsonObject, previous: DysonState): ParseResult {
        return when (root[F.MSG]?.jsonPrimitive?.contentOrNull) {
            F.MSG_CURRENT_STATE, F.MSG_STATE_CHANGE -> {
                val productState = root[F.PRODUCT_STATE] as? JsonObject
                    ?: return ParseResult(previous, handled = false)
                ParseResult(mergeProductState(productState, previous), handled = true)
            }

            F.MSG_ENVIRONMENTAL -> {
                val data = root[F.DATA] as? JsonObject
                    ?: return ParseResult(previous, handled = false)
                ParseResult(mergeEnvironmental(data, previous), handled = true)
            }

            else -> ParseResult(previous, handled = false)
        }
    }

    private fun mergeProductState(state: JsonObject, previous: DysonState): DysonState {
        val power = if (family.usesLegacyFanMode) {
            state.field(F.FAN_MODE)?.let { it == "FAN" || it == "AUTO" } ?: previous.power
        } else {
            state.field(F.POWER)?.let { it == F.ON } ?: previous.power
        }

        val auto = if (family.usesLegacyFanMode) {
            state.field(F.FAN_MODE)?.let { it == "AUTO" } ?: previous.autoMode
        } else {
            state.field(F.AUTO)?.let { it == F.ON } ?: previous.autoMode
        }

        val rawSpeed = state.field(F.FAN_SPEED)
        val speed = when {
            rawSpeed == null -> previous.fanSpeed
            rawSpeed.equals("AUTO", ignoreCase = true) -> null
            else -> rawSpeed.toIntOrNull() ?: previous.fanSpeed
        }

        // Older machines report OION/OIOF, newer ones plain ON/OFF.
        val oscillation = state.field(F.OSCILLATION)?.let { it == F.ON || it == "OION" }
            ?: previous.oscillation

        val heatTargetC = state.field(F.HEAT_TARGET)?.toFloatOrNull()
            ?.let { kelvinTenthsToCelsius(it) } ?: previous.targetTemperatureC

        return previous.copy(
            connection = ConnectionStatus.ONLINE,
            power = power,
            autoMode = auto,
            fanSpeed = speed,
            oscillation = oscillation,
            nightMode = state.field(F.NIGHT_MODE)?.let { it == F.ON } ?: previous.nightMode,
            fanRunning = state.field(F.FAN_STATE)?.let { it == "FAN" } ?: previous.fanRunning,
            frontAirflow = state.field(F.AIRFLOW_DIRECTION)?.let { it == F.ON } ?: previous.frontAirflow,
            heating = state.field(F.HEAT_MODE)?.let { it == "HEAT" } ?: previous.heating,
            heatingActive = state.field(F.HEAT_STATE)?.let { it == "HEAT" } ?: previous.heatingActive,
            targetTemperatureC = heatTargetC,
            continuousMonitoring = state.field(F.MONITORING)?.let { it == F.ON }
                ?: previous.continuousMonitoring,
            hepaFilterPercent = state.percentField(F.HEPA_FILTER) ?: previous.hepaFilterPercent,
            carbonFilterPercent = state.percentField(F.CARBON_FILTER) ?: previous.carbonFilterPercent,
            filterHours = state.field(F.FILTER_HOURS)?.toIntOrNull() ?: previous.filterHours,
            errorCode = state.field(F.ERROR_CODE)?.takeIf { it.isNotBlank() && it != "NONE" && it != "OK" },
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    private fun mergeEnvironmental(data: JsonObject, previous: DysonState): DysonState {
        val pm25 = data.sensorInt(F.PM25) ?: data.sensorInt(F.PM25_ALT)
            ?: data.sensorInt(F.PARTICULATES_LINK)
        val pm10 = data.sensorInt(F.PM10) ?: data.sensorInt(F.PM10_ALT)
        val voc = data.sensorFloat(F.VOC, divisor = 10f) ?: data.sensorFloat(F.VOC_LINK)

        return previous.copy(
            connection = ConnectionStatus.ONLINE,
            temperatureC = data.sensorFloat(F.TEMPERATURE)?.let { kelvinTenthsToCelsius(it) }
                ?: previous.temperatureC,
            humidity = data.sensorInt(F.HUMIDITY) ?: previous.humidity,
            pm25 = pm25 ?: previous.pm25,
            pm10 = pm10 ?: previous.pm10,
            voc = voc ?: previous.voc,
            no2 = data.sensorFloat(F.NO2, divisor = 10f) ?: previous.no2,
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    companion object {
        fun kelvinTenthsToCelsius(tenths: Float): Float = tenths / 10f - 273.15f

        fun celsiusToKelvinTenths(celsius: Float): Int = Math.round((celsius + 273.15f) * 10f)

        /**
         * `product-state` values arrive either as a scalar or as a
         * `[previous, current]` pair inside `STATE-CHANGE`; the current value is
         * always the last element.
         */
        internal fun JsonObject.field(key: String): String? {
            return when (val element = this[key]) {
                is JsonPrimitive -> element.contentOrNull
                is JsonArray -> (element.lastOrNull() as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }

        /** Sensor values use string sentinels for "off", "warming up" and "failed". */
        private fun JsonObject.sensorRaw(key: String): String? {
            val raw = field(key) ?: return null
            return when (raw.uppercase()) {
                "OFF", "INIT", "FAIL", "NONE", "" -> null
                else -> raw
            }
        }

        private fun JsonObject.sensorInt(key: String): Int? = sensorRaw(key)?.toIntOrNull()

        private fun JsonObject.sensorFloat(key: String, divisor: Float = 1f): Float? =
            sensorRaw(key)?.toFloatOrNull()?.let { it / divisor }

        /** Filter percentages report "INV" when the filter type is not fitted. */
        private fun JsonObject.percentField(key: String): Int? {
            val raw = field(key) ?: return null
            if (raw.equals("INV", ignoreCase = true)) return null
            return raw.toIntOrNull()?.takeIf { it in 0..100 }
        }
    }
}
