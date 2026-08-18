package com.glasscontrol.dyson.data.local

import com.glasscontrol.dyson.core.DysonError
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real MQTT client against a broker that behaves like a Dyson.
 *
 * This is the closest thing to a machine on the bench: it exercises the actual
 * Paho handshake, the topics, the published payloads and the parsing of the
 * replies, so a protocol mistake fails here rather than silently on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class DysonMqttClientTest {

    private val device = DysonDevice(
        serial = "NN2-EU-ABC1234A",
        name = "Salon",
        deviceType = "438K",
        credential = "local-credential",
    )

    private lateinit var broker: FakeDysonBroker

    private val stateReply = """
        {"msg":"CURRENT-STATE","product-state":{
          "fpwr":"ON","fnsp":"0004","auto":"OFF","oson":"OION","nmod":"OFF","hflr":"0090"}}
    """.trimIndent()

    private val environmentalReply = """
        {"msg":"ENVIRONMENTAL-CURRENT-SENSOR-DATA","data":{"tact":"2942","hact":"0047","p25r":"0004"}}
    """.trimIndent()

    @Before
    fun setUp() {
        broker = FakeDysonBroker(
            expectedUser = device.serial,
            expectedPassword = device.credential,
        ).apply {
            statusTopic = device.statusTopic
            replies = mapOf(
                "REQUEST-CURRENT-STATE" to { stateReply },
                "REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA" to { environmentalReply },
            )
            start()
        }
    }

    @After
    fun tearDown() = broker.stop()

    private fun client() = DysonMqttClient(readTimeoutMs = 4_000L, port = broker.port)

    @Test
    fun `reads a full state over a real MQTT session`() = runTest {
        val state = client().withSession(device, "127.0.0.1") { session ->
            session.readState(DysonState())
        }

        assertEquals(ConnectionStatus.ONLINE, state.connection)
        assertTrue(state.power)
        assertEquals(4, state.fanSpeed)
        assertTrue(state.oscillation)
        assertEquals(90, state.hepaFilterPercent)
        // Both replies must be merged into one state.
        assertEquals(21.05f, state.temperatureC!!, 0.01f)
        assertEquals(47, state.humidity)
        assertEquals(4, state.pm25)
    }

    @Test
    fun `subscribes to the machine's status topic and publishes to its command topic`() = runTest {
        client().withSession(device, "127.0.0.1") { session ->
            session.readState(DysonState())
        }

        assertEquals(listOf("438K/NN2-EU-ABC1234A/status/current"), broker.subscriptions.toList())
        assertTrue(
            "Every publish must go to the command topic",
            broker.received.all { it.first == "438K/NN2-EU-ABC1234A/command" },
        )
        val messages = broker.received.map { it.second }
        assertTrue(messages.any { it.contains("REQUEST-CURRENT-STATE") })
        assertTrue(messages.any { it.contains("REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA") })
    }

    @Test
    fun `applies a command and returns the state the machine confirms`() = runTest {
        // The machine answers a STATE-SET with a STATE-CHANGE, as a real one does.
        broker.replies = broker.replies + ("STATE-SET" to {
            """{"msg":"STATE-CHANGE","product-state":{"fpwr":["OFF","ON"],"fnsp":["0004","0008"]}}"""
        })

        val encoder = CommandEncoder(device.family)
        val data = encoder.encode(
            com.glasscontrol.dyson.domain.model.DysonCommand.SetFanSpeed(8),
            DysonState(power = true),
        )!!

        val state = client().withSession(device, "127.0.0.1") { session ->
            session.applyCommand(data, DysonState(power = true))
        }

        assertTrue(state.power)
        assertEquals(8, state.fanSpeed)

        val stateSet = broker.received.map { it.second }.first { it.contains("STATE-SET") }
        assertTrue("Commands must be marked as coming from a local app", stateSet.contains("LAPP"))
        assertTrue(stateSet.contains("\"fnsp\":\"0008\""))
    }

    @Test
    fun `a rejected credential surfaces as an invalid credential error`() = runTest {
        broker.rejectCredentials = true

        val error = runCatching {
            client().withSession(device, "127.0.0.1") { it.readState(DysonState()) }
        }.exceptionOrNull()

        assertTrue(
            "Expected InvalidCredential but got $error",
            error is DysonError.InvalidCredential,
        )
    }

    @Test
    fun `an unreachable machine fails instead of hanging`() = runTest {
        broker.stop()

        val error = runCatching {
            DysonMqttClient(connectTimeoutMs = 2_000L, readTimeoutMs = 2_000L, port = broker.port)
                .withSession(device, "127.0.0.1") { it.readState(DysonState()) }
        }.exceptionOrNull()

        assertTrue("Expected a domain error but got $error", error is DysonError)
    }

    @Test
    fun `a silent machine returns what it has rather than hanging forever`() = runTest {
        // A machine with its sensors off never answers the environmental request.
        broker.replies = mapOf("REQUEST-CURRENT-STATE" to { stateReply })

        val state = DysonMqttClient(readTimeoutMs = 1_500L, port = broker.port)
            .withSession(device, "127.0.0.1") { it.readState(DysonState()) }

        assertTrue("The product state still arrived", state.power)
        assertEquals(4, state.fanSpeed)
        assertFalse("No sensor data was sent, so none should be invented", state.humidity != null)
    }
}
