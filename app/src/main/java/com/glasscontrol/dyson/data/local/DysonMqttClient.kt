package com.glasscontrol.dyson.data.local

import com.glasscontrol.dyson.core.DysonError
import com.glasscontrol.dyson.core.Redact
import com.glasscontrol.dyson.core.logD
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A short-lived local MQTT session with one machine.
 *
 * Dyson machines run an unauthenticated-to-the-internet broker on port 1883 that
 * only accepts the serial as username and the local credential as password. We
 * open a session per operation and close it again, rather than holding a socket:
 * a widget tap costs a couple of seconds of radio instead of a permanent
 * connection.
 */
class DysonMqttClient(
    private val connectTimeoutMs: Long = 6_000L,
    private val readTimeoutMs: Long = 5_000L,
) {

    /** An open session. Only valid inside [withSession]. */
    class Session internal constructor(
        private val client: MqttAsyncClient,
        private val device: DysonDevice,
        private val messages: Channel<String>,
        private val parser: DysonStateParser,
        private val readTimeoutMs: Long,
    ) {
        /**
         * Asks for the full picture and merges every reply until both the product
         * state and the sensor data have arrived, or the read window closes.
         *
         * Machines answer the two requests in separate messages and some never send
         * environmental data at all (sensors off), so waiting for both is
         * best-effort: whatever arrived is returned.
         */
        suspend fun readState(previous: DysonState): DysonState {
            publish(MqttMessages.requestState())
            publish(MqttMessages.requestEnvironmental())
            return collectState(previous, expectMessages = 2)
        }

        /** Publishes a STATE-SET and waits briefly for the machine to confirm. */
        suspend fun applyCommand(data: Map<String, String>, previous: DysonState): DysonState {
            publish(MqttMessages.stateSet(data), qos = 1)
            // The machine echoes a STATE-CHANGE; if it does not, re-read explicitly
            // so the returned state is never a guess.
            val afterChange = collectState(previous, expectMessages = 1)
            return if (afterChange.lastUpdatedEpochMs > previous.lastUpdatedEpochMs) {
                afterChange
            } else {
                readState(previous)
            }
        }

        /** Re-asks for state and sensors without waiting for the reply. */
        suspend fun requestRefresh() {
            publish(MqttMessages.requestState())
            publish(MqttMessages.requestEnvironmental())
        }

        /**
         * Streams every state update the machine pushes until cancelled.
         *
         * Used by the app while a control screen is open: machines emit a
         * STATE-CHANGE on their own whenever something moves, so this needs no
         * polling of its own.
         */
        suspend fun observe(initial: DysonState, onState: suspend (DysonState) -> Unit) {
            var current = initial
            while (true) {
                val payload = messages.receive()
                val result = parser.parse(payload, current)
                if (result.handled) {
                    current = result.state
                    onState(current)
                }
            }
        }

        /** Drains incoming payloads into a state, stopping once satisfied. */
        private suspend fun collectState(previous: DysonState, expectMessages: Int): DysonState {
            var current = previous.copy(connection = ConnectionStatus.ONLINE)
            var handled = 0
            withTimeoutOrNull(readTimeoutMs) {
                while (handled < expectMessages) {
                    val payload = messages.receive()
                    val result = parser.parse(payload, current)
                    if (result.handled) {
                        current = result.state
                        handled++
                    }
                }
            }
            return current
        }

        private suspend fun publish(payload: String, qos: Int = 0) {
            withContext(Dispatchers.IO) {
                client.publish(device.commandTopic, payload.toByteArray(), qos, false)
            }
        }
    }

    /**
     * Connects, runs [block], and always disconnects afterwards.
     *
     * @param host the machine's address on the local network.
     */
    suspend fun <T> withSession(
        device: DysonDevice,
        host: String,
        block: suspend (Session) -> T,
    ): T = withContext(Dispatchers.IO) {
        val messages = Channel<String>(capacity = Channel.BUFFERED)
        // MQTT 3.1 caps the client id at 23 characters.
        val clientId = "dgc" + UUID.randomUUID().toString().replace("-", "").take(16)
        val client = MqttAsyncClient("tcp://$host:$MQTT_PORT", clientId, MemoryPersistence())

        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                messages.close(cause ?: DysonError.Unreachable())
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                messages.trySend(String(message.payload, Charsets.UTF_8))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        try {
            logD("MQTT connect ${Redact.host(host)} serial=${Redact.serial(device.serial)}")
            client.awaitConnect(device)
            client.awaitSubscribe(device.statusTopic)
            block(Session(client, device, messages, DysonStateParser(device.family), readTimeoutMs))
        } catch (error: TimeoutCancellationException) {
            throw DysonError.Timeout()
        } catch (error: MqttException) {
            throw error.toDomainError()
        } finally {
            messages.close()
            runCatching { client.disconnectForcibly(DISCONNECT_QUIESCE_MS, DISCONNECT_TIMEOUT_MS) }
            runCatching { client.close(true) }
        }
    }

    private suspend fun MqttAsyncClient.awaitConnect(device: DysonDevice) {
        val options = MqttConnectOptions().apply {
            // Dyson brokers speak MQTT 3.1 only.
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1
            userName = device.serial
            password = device.credential.toCharArray()
            isCleanSession = true
            isAutomaticReconnect = false
            connectionTimeout = (connectTimeoutMs / 1000).toInt().coerceAtLeast(1)
            keepAliveInterval = KEEP_ALIVE_SECONDS
        }
        awaitToken { listener -> connect(options, null, listener) }
    }

    private suspend fun MqttAsyncClient.awaitSubscribe(topic: String) {
        awaitToken { listener -> subscribe(topic, 0, null, listener) }
    }

    /** Bridges a Paho async token to a coroutine. */
    private suspend fun awaitToken(
        start: (org.eclipse.paho.client.mqttv3.IMqttActionListener) -> Unit,
    ) = suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
        val listener = object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
            override fun onSuccess(token: IMqttToken?) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onFailure(token: IMqttToken?, error: Throwable?) {
                if (!continuation.isActive) return
                val mapped = (error as? MqttException)?.toDomainError()
                    ?: DysonError.Unreachable(error)
                continuation.resumeWithException(mapped)
            }
        }
        runCatching { start(listener) }.onFailure { error ->
            if (continuation.isActive) {
                continuation.resumeWithException(
                    (error as? MqttException)?.toDomainError() ?: DysonError.Unreachable(error)
                )
            }
        }
    }

    private fun MqttException.toDomainError(): DysonError = when (reasonCode.toInt()) {
        MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
        MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(),
        -> DysonError.InvalidCredential()

        MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt(),
        MqttException.REASON_CODE_CONNECTION_LOST.toInt(),
        -> DysonError.Timeout()

        else -> DysonError.Unreachable(this)
    }

    companion object {
        const val MQTT_PORT = 1883
        private const val KEEP_ALIVE_SECONDS = 20
        private const val DISCONNECT_QUIESCE_MS = 200L
        private const val DISCONNECT_TIMEOUT_MS = 500L
    }
}
