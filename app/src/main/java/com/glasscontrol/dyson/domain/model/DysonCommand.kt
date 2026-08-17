package com.glasscontrol.dyson.domain.model

import kotlinx.serialization.Serializable

/**
 * A user intent, independent of how the machine encodes it.
 *
 * Translation into MQTT `STATE-SET` fields happens in
 * [com.glasscontrol.dyson.data.local.CommandEncoder], which is the only place that
 * knows about family-specific field names.
 */
@Serializable
sealed interface DysonCommand {

    @Serializable
    data class SetPower(val on: Boolean) : DysonCommand

    @Serializable
    data class SetFanSpeed(val speed: Int) : DysonCommand

    @Serializable
    data class SetAutoMode(val enabled: Boolean) : DysonCommand

    @Serializable
    data class SetOscillation(val enabled: Boolean) : DysonCommand

    @Serializable
    data class SetNightMode(val enabled: Boolean) : DysonCommand

    @Serializable
    data class SetHeating(val enabled: Boolean) : DysonCommand

    /** Target temperature in Celsius; the encoder converts to the Kelvin field. */
    @Serializable
    data class SetTargetTemperature(val celsius: Float) : DysonCommand

    @Serializable
    data class SetAirflowDirection(val front: Boolean) : DysonCommand

    /** Step the speed relative to the current one, clamped to the machine's range. */
    @Serializable
    data class StepFanSpeed(val delta: Int) : DysonCommand
}
