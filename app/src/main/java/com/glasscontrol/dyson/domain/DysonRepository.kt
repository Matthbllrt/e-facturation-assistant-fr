package com.glasscontrol.dyson.domain

import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.coroutines.flow.Flow

/** A device found on the local network but not necessarily configured yet. */
data class DiscoveredDevice(
    val serial: String,
    val deviceType: String,
    val host: String,
)

/**
 * The single seam between the app/widget and the Dyson protocol.
 *
 * Implementations own connection lifetime: callers issue one-shot operations and
 * never hold a socket, which is what keeps widget taps cheap on battery.
 */
interface DysonRepository {

    /** Currently configured device, or null before onboarding completes. */
    val device: Flow<DysonDevice?>

    /** Last known state, served from cache immediately and refreshed in place. */
    val state: Flow<DysonState>

    /** Capabilities of the configured device, narrowed by observed fields. */
    val capabilities: Flow<DysonCapabilities>

    /** Opens a local session and keeps it until [disconnect]; used by the app UI. */
    suspend fun connect(): Result<Unit>

    suspend fun disconnect()

    /** Browses the local network over mDNS for Dyson machines. */
    fun discoverDevices(timeoutMs: Long = 6_000L): Flow<DiscoveredDevice>

    /** Cached state without touching the network. */
    suspend fun getState(): DysonState

    /** Round-trips to the machine and returns the freshly read state. */
    suspend fun refreshState(): Result<DysonState>

    suspend fun setPower(on: Boolean): Result<DysonState>

    suspend fun setFanSpeed(speed: Int): Result<DysonState>

    suspend fun setAutoMode(enabled: Boolean): Result<DysonState>

    suspend fun setOscillation(enabled: Boolean): Result<DysonState>

    suspend fun setNightMode(enabled: Boolean): Result<DysonState>

    suspend fun setHeating(enabled: Boolean): Result<DysonState>

    suspend fun setTemperature(celsius: Float): Result<DysonState>

    suspend fun setAirflowDirection(front: Boolean): Result<DysonState>

    /** Generic entry point used by the widget, which serialises intents. */
    suspend fun execute(command: DysonCommand): Result<DysonState>

    /** Stores a device (from cloud or manual setup) and makes it the active one. */
    suspend fun saveDevice(device: DysonDevice)

    /** Wipes credentials and cached state. */
    suspend fun forgetDevice()
}
