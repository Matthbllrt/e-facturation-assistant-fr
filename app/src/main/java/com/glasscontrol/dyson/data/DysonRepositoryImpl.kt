package com.glasscontrol.dyson.data

import com.glasscontrol.dyson.core.LogArea
import com.glasscontrol.dyson.core.DysonError
import com.glasscontrol.dyson.core.Redact
import com.glasscontrol.dyson.core.logD
import com.glasscontrol.dyson.core.logW
import com.glasscontrol.dyson.data.discovery.DysonDiscovery
import com.glasscontrol.dyson.data.discovery.LanScanner
import com.glasscontrol.dyson.data.local.CommandEncoder
import com.glasscontrol.dyson.data.local.DysonMqttClient
import com.glasscontrol.dyson.data.store.DeviceConfigStore
import com.glasscontrol.dyson.security.CredentialStore
import com.glasscontrol.dyson.data.store.StateCache
import com.glasscontrol.dyson.domain.CapabilityResolver
import com.glasscontrol.dyson.domain.ConnectionDiagnostics
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
import kotlinx.coroutines.flow.firstOrNull
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
    private val credentialStore: CredentialStore,
    private val stateCache: StateCache,
    private val discovery: DysonDiscovery,
    private val lanScanner: LanScanner,
    private val mqttClient: DysonMqttClient = DysonMqttClient(),
    private val scope: CoroutineScope,
    private val onStateChanged: suspend (DysonState) -> Unit = {},
) : DysonRepository {

    private val operationLock = Mutex()
    private var liveSessionJob: Job? = null

    /**
     * The streaming session, when the app has one open.
     *
     * A Dyson machine runs a small embedded broker that accepts very few
     * simultaneous clients, so opening a second connection for a command while
     * this one is live is what made commands fail whenever the app was in the
     * foreground. Commands are routed through this session instead.
     */
    @Volatile
    private var liveSession: DysonMqttClient.Session? = null

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
                        // Set before unblocking connect(): a command issued in
                        // between would otherwise still dial a second connection.
                        liveSession = session
                        started.complete(Result.success(Unit))
                        try {
                            session.observe(initial) { updated -> publish(updated) }
                        } finally {
                            liveSession = null
                        }
                    }
                }
            }.onFailure { error ->
                logW(LogArea.CONNECTION, "Live session ended", error)
                liveSession = null
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
        liveSession = null
        liveSessionJob?.cancel()
        liveSessionJob = null
    }

    override fun discoverDevices(timeoutMs: Long): Flow<DiscoveredDevice> =
        discovery.discover(timeoutMs)

    override suspend fun refreshState(): Result<DysonState> {
        // With a live session open, asking is enough: the observer delivers the
        // answer into the cache as soon as the machine replies.
        liveSession?.let { session ->
            return runCatching {
                session.requestRefresh()
                stateCache.read()
            }.onFailure { logW(LogArea.CONNECTION, "Refresh over the live session failed", it) }
        }
        return runOperation { session, current -> session.readState(current) }
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

        val optimistic = encoder.predict(command, current)
        publish(optimistic.copy(connection = ConnectionStatus.CONNECTING))

        // Reuse the streaming session rather than opening a competing connection.
        liveSession?.let { session ->
            return runCatching {
                session.sendCommand(data)
                publish(optimistic.copy(connection = ConnectionStatus.ONLINE))
                optimistic
            }.onFailure { error ->
                logW(LogArea.CONNECTION, "Command over the live session failed", error)
                publish(current.copy(connection = ConnectionStatus.OFFLINE))
            }
        }

        return runOperation { session, latest ->
            session.applyCommand(data, latest)
        }.onFailure {
            // The optimistic value was never confirmed; fall back to what we knew.
            publish(current.copy(connection = ConnectionStatus.OFFLINE))
        }
    }

    override suspend fun saveDevice(device: DysonDevice) {
        if (device.credential.isNotEmpty()) {
            val stored = credentialStore.putCredential(device.serial, device.credential)
            if (!stored) {
                throw DysonError.Cloud(
                    "Impossible d'enregistrer l'identifiant local en sécurité sur cet appareil."
                )
            }
        }
        deviceConfigStore.save(device)
    }

    override suspend fun previewCommand(command: DysonCommand) {
        val device = deviceConfigStore.device.first() ?: return
        val current = stateCache.read()
        val encoder = CommandEncoder(device.family)
        if (encoder.encode(command, current) == null) return
        publish(encoder.predict(command, current).copy(connection = ConnectionStatus.CONNECTING))
    }

    override suspend fun setHost(host: String?) {
        val stored = deviceConfigStore.device.first() ?: return
        deviceConfigStore.save(stored.copy(host = host?.takeIf { it.isNotBlank() }))
        disconnect()
    }

    override suspend fun diagnose(): ConnectionDiagnostics {
        val stored = deviceConfigStore.device.first()
            ?: return ConnectionDiagnostics(
                configured = false,
                credentialStored = false,
                storedHost = null,
                resolvedHost = null,
                reachable = false,
                summary = "Aucun Dyson configuré.",
            )

        val credential = credentialStore.getCredential(stored.serial)
        if (credential == null) {
            return ConnectionDiagnostics(
                configured = true,
                credentialStored = false,
                storedHost = stored.host,
                resolvedHost = null,
                reachable = false,
                summary = "L'identifiant local est introuvable dans le Keystore. " +
                    "Reconfigurez l'appareil dans Réglages.",
            )
        }

        val device = stored.copy(credential = credential)

        // If the app is already streaming, the connection is proven and probing
        // again would open exactly the second connection this all guards against.
        liveSession?.let {
            return ConnectionDiagnostics(
                configured = true,
                credentialStored = true,
                storedHost = stored.host,
                resolvedHost = stored.host,
                reachable = true,
                summary = "Connexion locale active.",
            )
        }

        // Prefer the stored address, but only if it still answers.
        val resolved = device.host?.takeIf { accepts(device, it) } ?: resolveHost(device)

        if (resolved == null) {
            return ConnectionDiagnostics(
                configured = true,
                credentialStored = true,
                storedHost = stored.host,
                resolvedHost = null,
                reachable = false,
                summary = "Appareil introuvable sur ce réseau. Vérifiez que le téléphone " +
                    "est sur le même Wi-Fi que le Dyson, ou saisissez son adresse IP.",
            )
        }

        if (resolved != stored.host) deviceConfigStore.updateHost(resolved)

        val reachable = runCatching {
            mqttClient.withSession(device, resolved) { session ->
                publish(session.readState(stateCache.read()))
                true
            }
        }.getOrElse { error ->
            return ConnectionDiagnostics(
                configured = true,
                credentialStored = true,
                storedHost = stored.host,
                resolvedHost = resolved,
                reachable = false,
                summary = when (error) {
                    is DysonError.InvalidCredential ->
                        "L'appareil a refusé l'identifiant local. Reconfigurez-le."
                    else -> "Connexion à $resolved impossible : ${error.message}"
                },
            )
        }

        return ConnectionDiagnostics(
            configured = true,
            credentialStored = true,
            storedHost = stored.host,
            resolvedHost = resolved,
            reachable = reachable,
            summary = "Connexion établie avec $resolved.",
        )
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
            logW(LogArea.CONNECTION, "Operation failed", error)
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
                    logD(LogArea.CONNECTION, "Stored host unreachable, rediscovering")
                }
        }

        val discovered = resolveHost(device) ?: throw DysonError.Unreachable()
        deviceConfigStore.updateHost(discovered)
        logD(LogArea.CONNECTION, "Relocated ${Redact.serial(device.serial)} at ${Redact.host(discovered)}")
        return block(discovered)
    }

    /**
     * Locates the machine on the network.
     *
     * mDNS is tried first because it identifies the machine by serial. Plenty of
     * home networks never deliver multicast though, and without a second route
     * the app would simply never work there, so a subnet sweep follows: any host
     * with the MQTT port open is probed, and the one that accepts this machine's
     * credentials is by definition the right one.
     */
    suspend fun resolveHost(device: DysonDevice): String? {
        discoverHost(device.serial)?.let { return it }

        logD(LogArea.CONNECTION, "mDNS found nothing, sweeping the subnet")
        val candidates = runCatching { lanScanner.scan() }.getOrDefault(emptyList())
        return candidates.firstOrNull { candidate -> accepts(device, candidate) }
    }

    /** True when [host] is a broker that accepts this machine's credentials. */
    private suspend fun accepts(device: DysonDevice, host: String): Boolean =
        runCatching {
            probeClient.withSession(device, host) { true }
        }.getOrDefault(false)

    private suspend fun discoverHost(serial: String): String? =
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            // firstOrNull, not first: mDNS closes without emitting on networks that
            // drop multicast, and first would throw there rather than letting the
            // subnet sweep take over.
            discovery.discover(DISCOVERY_TIMEOUT_MS)
                .firstOrNull { it.serial.equals(serial, ignoreCase = true) }
                ?.host
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

    /**
     * Short timeouts: a probe only has to prove the broker answers.
     *
     * It must dial the same port as the real client, or it would reject the very
     * host the next connection is about to use.
     */
    private val probeClient = DysonMqttClient(
        connectTimeoutMs = 2_500L,
        readTimeoutMs = 2_000L,
        port = mqttClient.port,
    )

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 6_000L
        const val CONNECT_RESULT_TIMEOUT_MS = 8_000L
    }
}
