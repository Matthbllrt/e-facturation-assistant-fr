package com.glasscontrol.dyson.data.local

import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Encodes a [DysonCommand] into the `STATE-SET` fields the machine expects.
 *
 * Encoding is family-dependent: Link machines drive power and auto through the
 * single `fmod` field, so turning auto off there means selecting `FAN` rather
 * than writing an `auto` flag. Some commands also need the current state — for
 * instance a speed change must keep the machine powered on.
 */
class CommandEncoder(private val family: DysonFamily) {

    /**
     * @return the `data` map for a STATE-SET message, or null when the command
     *   makes no sense for this family (the caller then does nothing rather than
     *   sending a payload the machine would reject).
     */
    fun encode(command: DysonCommand, current: DysonState): Map<String, String>? = when (command) {
        is DysonCommand.SetPower -> encodePower(command.on, current)
        is DysonCommand.SetFanSpeed -> encodeSpeed(command.speed)
        is DysonCommand.StepFanSpeed -> encodeSpeed(steppedSpeed(command.delta, current))
        is DysonCommand.SetAutoMode -> encodeAuto(command.enabled)
        is DysonCommand.SetOscillation -> encodeOscillation(command.enabled, current)
        is DysonCommand.SetNightMode -> mapOf(F.NIGHT_MODE to onOff(command.enabled))
        is DysonCommand.SetHeating -> encodeHeating(command.enabled)
        is DysonCommand.SetTargetTemperature -> encodeTemperature(command.celsius)
        is DysonCommand.SetAirflowDirection -> encodeAirflow(command.front)
    }

    /** Resulting state if the machine accepts the command, for optimistic UI. */
    fun predict(command: DysonCommand, current: DysonState): DysonState = when (command) {
        is DysonCommand.SetPower -> current.copy(power = command.on, fanRunning = command.on)
        is DysonCommand.SetFanSpeed ->
            current.copy(fanSpeed = clampSpeed(command.speed), power = true, autoMode = false)

        is DysonCommand.StepFanSpeed ->
            current.copy(fanSpeed = steppedSpeed(command.delta, current), power = true, autoMode = false)

        is DysonCommand.SetAutoMode -> current.copy(
            autoMode = command.enabled,
            power = if (command.enabled) true else current.power,
            // In auto the machine chooses the speed, so the numeric value is unknown.
            fanSpeed = if (command.enabled) null else current.fanSpeed,
        )

        is DysonCommand.SetOscillation -> current.copy(oscillation = command.enabled)
        is DysonCommand.SetNightMode -> current.copy(nightMode = command.enabled)
        is DysonCommand.SetHeating -> current.copy(heating = command.enabled)
        is DysonCommand.SetTargetTemperature ->
            current.copy(targetTemperatureC = clampTemperature(command.celsius), heating = true)

        is DysonCommand.SetAirflowDirection -> current.copy(frontAirflow = command.front)
    }

    private fun encodePower(on: Boolean, current: DysonState): Map<String, String> =
        if (family.usesLegacyFanMode) {
            // Restore the previous preset when switching back on.
            val mode = if (!on) F.OFF else if (current.autoMode) "AUTO" else "FAN"
            mapOf(F.FAN_MODE to mode)
        } else {
            mapOf(F.POWER to onOff(on))
        }

    private fun encodeSpeed(speed: Int): Map<String, String> {
        val value = padded(clampSpeed(speed))
        return if (family.usesLegacyFanMode) {
            mapOf(F.FAN_MODE to "FAN", F.FAN_SPEED to value)
        } else {
            // Setting a manual speed implies leaving auto and powering on.
            mapOf(F.POWER to F.ON, F.AUTO to F.OFF, F.FAN_SPEED to value)
        }
    }

    private fun encodeAuto(enabled: Boolean): Map<String, String> =
        if (family.usesLegacyFanMode) {
            mapOf(F.FAN_MODE to if (enabled) "AUTO" else "FAN")
        } else if (enabled) {
            mapOf(F.POWER to F.ON, F.AUTO to F.ON)
        } else {
            mapOf(F.AUTO to F.OFF)
        }

    private fun encodeOscillation(enabled: Boolean, current: DysonState): Map<String, String>? {
        if (!supportsOscillation()) return null
        if (family.usesLegacyFanMode) return mapOf(F.OSCILLATION to onOff(enabled))

        // A machine that reports OION/OIOF silently ignores ON/OFF, so the token
        // it last used decides which dialect to answer in.
        val legacyDialect = current.oscillationRaw in LEGACY_OSCILLATION_TOKENS
        val value = when {
            legacyDialect && enabled -> "OION"
            legacyDialect -> "OIOF"
            enabled -> F.ON
            else -> F.OFF
        }

        if (!enabled) return mapOf(F.OSCILLATION to value)

        // Switching oscillation on also needs the machine powered, and the angle
        // window replayed, otherwise some models accept the flag and do nothing.
        val low = current.oscillationAngleLow
        val high = current.oscillationAngleHigh
        return if (low != null && high != null && high - low >= MIN_OSCILLATION_SPAN) {
            mapOf(
                F.OSCILLATION to value,
                F.POWER to F.ON,
                F.OSC_ANGLE_PRESET to "CUST",
                F.OSC_LOW to padded(low),
                F.OSC_HIGH to padded(high),
            )
        } else {
            mapOf(F.OSCILLATION to value, F.POWER to F.ON)
        }
    }

    private fun encodeHeating(enabled: Boolean): Map<String, String>? {
        if (!family.supportsHeating) return null
        return mapOf(F.HEAT_MODE to if (enabled) "HEAT" else F.OFF)
    }

    private fun encodeTemperature(celsius: Float): Map<String, String>? {
        if (!family.supportsHeating) return null
        val kelvinTenths = DysonStateParser.celsiusToKelvinTenths(clampTemperature(celsius))
        return mapOf(
            F.HEAT_MODE to "HEAT",
            F.HEAT_TARGET to padded(kelvinTenths),
        )
    }

    private fun encodeAirflow(front: Boolean): Map<String, String>? {
        if (family.usesLegacyFanMode) return null
        return mapOf(F.AIRFLOW_DIRECTION to onOff(front))
    }

    private fun supportsOscillation(): Boolean = family != DysonFamily.BIG_QUIET

    private fun steppedSpeed(delta: Int, current: DysonState): Int =
        steppedSpeed(current.fanSpeed, delta)

    companion object {
        const val MIN_SPEED = 1
        const val MAX_SPEED = 10
        private const val DEFAULT_SPEED = 4

        /** `oson` tokens used by the older Pure Cool generation. */
        private val LEGACY_OSCILLATION_TOKENS = setOf("OION", "OIOF")

        /** The machine rejects an angle window narrower than this. */
        private const val MIN_OSCILLATION_SPAN = 30

        /** Dyson's heating range, 274-310 K, expressed in Celsius. */
        const val MIN_TEMPERATURE_C = 1f
        const val MAX_TEMPERATURE_C = 37f

        fun clampSpeed(speed: Int): Int = speed.coerceIn(MIN_SPEED, MAX_SPEED)

        /**
         * The speed one step away from [current].
         *
         * From auto there is no numeric speed to step from, so stepping starts at
         * a usable mid speed rather than jumping to 1.
         */
        fun steppedSpeed(current: Int?, delta: Int): Int {
            val base = current ?: if (delta > 0) DEFAULT_SPEED else DEFAULT_SPEED + 1
            return clampSpeed(base + delta)
        }

        fun clampTemperature(celsius: Float): Float =
            celsius.coerceIn(MIN_TEMPERATURE_C, MAX_TEMPERATURE_C)

        fun onOff(value: Boolean): String = if (value) F.ON else F.OFF

        /** Numeric Dyson fields are zero-padded to four characters. */
        fun padded(value: Int): String = value.toString().padStart(4, '0')

        /** MQTT timestamps are UTC, second precision, e.g. 2026-08-17T20:15:00Z. */
        fun mqttTime(now: Long = System.currentTimeMillis()): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(now))
        }
    }
}
