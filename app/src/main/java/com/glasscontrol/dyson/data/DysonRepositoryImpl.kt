package com.glasscontrol.dyson.data

import com.glasscontrol.dyson.core.DysonError
import com.glasscontrol.dyson.core.Redact
import com.glasscontrol.dyson.core.logD
import com.glasscontrol.dyson.core.logW
import com.glasscontrol.dyson.data.discovery.DysonDiscovery
import com.glasscontrol.dyson.data.local.CommandEncoder
import com.glasscontrol.dyson.data.local.DysonMqttClient
import com.glasscontrol.dyson.data.store.DeviceConfigStore
import com.glasscontrol.dyson.data.store.SecureCredentialStore
import com.glasscontrol.dyson.data.store.StateCache
import com.glasscontrol.dyson.domain.CapabilityResolver
import com.glasscontrol.dyson.domain.DiscoveredDevice
import com.glasscontrol.dyson.domain.DysonRepository
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default [DysonRepository]: local MQTT control with cached state.
 *
 * Operations are serialised through [operationLock] because a Dyson broker
 * accepts only a small number of concurrent sessions — two overlapping widget
 * taps would otherwise race for the same socket budget.
 *
 * @param onStateChanged invoked after every state write, so widgets can repaint
 *   without the data layer knowing anything about Glance.
 */
class DysonRepositoryImpl(
    private val deviceConfigStore: DeviceConfigStore,
    private val credentialStore: SecureCredentialStore,
    private val stateCache: StateCache,
    private val discovery: DysonDiscovery,
    private val mqttClient: DysonMqttClient = DysonMqttClient(),
    private val scope: CoroutineScope,
    private val onStateChanged: suspend (DysonState) -> Unit = {},
) : DysonRepository {

    private val operationLock = Mutex()
    private var liveSessionJob: Job? = null

    override val device: Flow<DysonDevice?> = deviceConfigStore.device

    override val state: Flow<DysonState> = stateCache.state

    override val capabilities: Flow<DysonCapabilities> =
        combine(deviceConfigStore.device, stateCache.state) { device, state ->
            CapabilityResolver.resolve(
                family = device?.family ?: com.glasscontrol.dyson.domain.model.DysonFamily.UNSUPPORTED,
                state = state,
            )
        }

    override suspend fun getState(): DysonState = stateCache.read()

    /**
     * Opens a live session that streams updates into the cache.
     *
     * Only the foreground app uses this; the widget never holds a session.
     */
    override suspend fun connect(): Result<Unit> {
        val device = resolveDevice().getOrElse { return Result.failure(it) }
        disconnect()

        val started = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        liveSessionJob = scope.launch {
            runCatching {
                withHost(device) { host ->
                    mqttClient.withSession(device, host) { session ->
                        val initial = session.readState(stateCache.read())
                        publish(initial)
                        started.complete(Result.success(Unit))
                        session.observe(initial) { updated -> publish(updated) }
                    }
                }
            }.onFailure { error ->
                logW("Live session ended", error)
                if (!started.isCompleted) started.complete(Result.failure(error))
                publish(stateCache.read().copy(connection = ConnectionStatus.OFFLINE))
            }
        }

        // Give the session a moment to prove it works, but never block the UI for
        // longer than a connect attempt would take.
        return withTimeoutOrNull(CONNECT_RESULT_TIMEOUT_MS) { started.await() }
            ?: Result.success(Unit)
    }

    override suspend fun disconnect() {
        liveSessionJob?.cancel()
        liveSessionJob = null
    }

    override fun discoverDevices(timeoutMs: Long): Flow<DiscoveredDevice> =
        discovery.discover(timeoutMs)

    override suspend fun refreshState(): Result<DysonState> = runOperation { session, current ->
        session.readState(current)
    }

    override suspend fun setPower(on: Boolean) = execute(DysonCommand.SetPower(on))

    override suspend fun setFanSpeed(speed: Int) = execute(DysonCommand.SetFanSpeed(speed))

    override suspend fun setAutoMode(enabled: Boolean) = execute(DysonCommand.SetAutoMode(enabled))

    override suspend fun setOscillation(enabled: Boolean) =
        execute(DysonCommand.SetOscillation(enabled))

    override suspend fun setNightMode(enabled: Boolean) = execute(DysonCommand.SetNightMode(enabled))

    override suspend fun setHeating(enabled: Boolean) = execute(DysonCommand.SetHeating(enabled))

    override suspend fun setTemperature(celsius: Float) =
        execute(DysonCommand.SetTargetTemperature(celsius))

    override suspend fun setAirflowDirection(front: Boolean) =
        execute(DysonCommand.SetAirflowDirection(front))

    /**
     * Applies a command, updating the cache optimistically first.
     *
     * The optimistic write is what makes a widget tap feel instant; the machine's
     * real answer overwrites it a moment later, so a rejected command corrects
     * itself rather than sticking.
     */
    override suspend fun execute(command: DysonCommand): Result<DysonState> {
        val device = resolveDevice().getOrElse { return Result.failure(it) }
        val encoder = CommandEncoder(device.family)
        val current = stateCache.read()

        val data = encoder.encode(command, current)
            // A command the family does not support is a no-op, never a crash.
            ?: return Result.success(current)

        publish(encoder.predict(command, current).copy(connection = ConnectionStatus.CONNECTING))

        return runOperation { session, latest ->
            session.applyCommand(data, latest)
        }.onFailure {
            // The optimistic value was never confirmed; fall back to what we knew.
            publish(current.copy(connection = ConnectionStatus.OFFLINE))
        }
    }

    override suspend fun saveDevice(device: DysonDevice) {
        deviceConfigStore.save(device)
        if (device.credential.isNotEmpty()) {
            credentialStore.putCredential(device.serial, device.credential)
        }
    }

    override suspend fun forgetDevice() {
        disconnect()
        deviceConfigStore.clear()
        credentialStore.clear()
        stateCache.clear()
        onStateChanged(DysonState())
    }

    /** Runs one short-lived session, with host resolution and a single retry. */
    private suspend fun runOperation(
        block: suspend (DysonMqttClient.Session, DysonState) -> DysonState,
    ): Result<DysonState> = operationLock.withLock {
        val device = resolveDevice().getOrElse { return@withLock Result.failure(it) }
        runCatching {
            withHost(device) { host ->
                mqttClient.withSession(device, host) { session ->
                    val result = block(session, stateCache.read())
                    publish(result)
                    result
                }
            }
        }.onFailure { error ->
            logW("Operation failed", error)
            publish(stateCache.read().copy(connection = ConnectionStatus.OFFLINE))
        }
    }

    /**
     * Runs [block] against the machine's address, rediscovering it if needed.
     *
     * A DHCP lease change is the common failure here, so a stale stored address
     * triggers one mDNS sweep and a retry before the operation is declared failed.
     */
    private suspend fun <T> withHost(device: DysonDevice, block: suspend (String) -> T): T {
        device.host?.let { stored ->
            runCatching { return block(stored) }
                .onFailure { error ->
                    if (error !is DysonError.Unreachable && error !is DysonError.Timeout) throw error
                    logD("Stored host unreachable, rediscovering")
                }
        }

        val discovered = discoverHost(device.serial) ?: throw DysonError.Unreachable()
        deviceConfigStore.updateHost(discovered)
        logD("Rediscovered ${Redact.serial(device.serial)} at ${Redact.host(discovered)}")
        return block(discovered)
    }

    private suspend fun discoverHost(serial: String): String? =
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            discovery.discover(DISCOVERY_TIMEOUT_MS)
                .first { it.serial.equals(serial, ignoreCase = true) }
                .host
        }

    /** Loads the configured machine together with its Keystore-held credential. */
    private suspend fun resolveDevice(): Result<DysonDevice> {
        val stored = deviceConfigStore.device.first()
            ?: return Result.failure(DysonError.NotConfigured())
        val credential = credentialStore.getCredential(stored.serial)
            ?: return Result.failure(DysonError.NotConfigured())
        return Result.success(stored.copy(credential = credential))
    }

    private suspend fun publish(state: DysonState) {
        stateCache.write(state)
        onStateChanged(state)
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 6_000L
        const val CONNECT_RESULT_TIMEOUT_MS = 8_000L
    }
}
