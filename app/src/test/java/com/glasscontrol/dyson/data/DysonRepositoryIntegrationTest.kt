package com.glasscontrol.dyson.data

import androidx.test.core.app.ApplicationProvider
import com.glasscontrol.dyson.data.discovery.DysonDiscovery
import com.glasscontrol.dyson.data.discovery.LanScanner
import com.glasscontrol.dyson.data.local.DysonMqttClient
import com.glasscontrol.dyson.data.local.FakeDysonBroker
import com.glasscontrol.dyson.data.store.DeviceConfigStore
import com.glasscontrol.dyson.data.store.StateCache
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.security.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A credential store without the hardware Keystore, which no test host has. */
private class InMemoryCredentialStore : CredentialStore {
    private val entries = mutableMapOf<String, String>()
    var failWrites = false

    override suspend fun putCredential(serial: String, credential: String): Boolean {
        if (failWrites) return false
        entries[serial] = credential
        return true
    }

    override suspend fun getCredential(serial: String): String? = entries[serial]

    override suspend fun clear() = entries.clear()
}

/**
 * Drives the whole control path against a broker that behaves like a Dyson.
 *
 * This is what proves the product actually works: a command goes through the
 * repository, over real MQTT, and the confirmed state lands in the cache the
 * widget reads — including what happens when the machine is not there.
 */
@RunWith(RobolectricTestRunner::class)
class DysonRepositoryIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val device = DysonDevice(
        serial = "NN2-EU-ABC1234A",
        name = "Salon",
        deviceType = "438K",
        credential = "local-credential",
        host = "127.0.0.1",
        model = "TP07",
    )

    private lateinit var broker: FakeDysonBroker
    private lateinit var credentials: InMemoryCredentialStore
    private lateinit var deviceStore: DeviceConfigStore
    private lateinit var stateCache: StateCache
    private lateinit var repository: DysonRepositoryImpl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stateReply = """
        {"msg":"CURRENT-STATE","product-state":{
          "fpwr":"ON","fnsp":"0006","auto":"OFF","oson":"OIOF","nmod":"OFF","hflr":"0090"}}
    """.trimIndent()

    private val environmentalReply =
        """{"msg":"ENVIRONMENTAL-CURRENT-SENSOR-DATA","data":{"tact":"2942","hact":"0047"}}"""

    @Before
    fun setUp() = runTest {
        broker = FakeDysonBroker(device.serial, device.credential).apply {
            statusTopic = device.statusTopic
            replies = mapOf(
                "REQUEST-CURRENT-STATE" to { stateReply },
                "REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA" to { environmentalReply },
            )
            start()
        }

        credentials = InMemoryCredentialStore()
        deviceStore = DeviceConfigStore(context)
        stateCache = StateCache(context)
        stateCache.clear()

        repository = DysonRepositoryImpl(
            deviceConfigStore = deviceStore,
            credentialStore = credentials,
            stateCache = stateCache,
            discovery = DysonDiscovery(context),
            lanScanner = LanScanner(context),
            mqttClient = DysonMqttClient(readTimeoutMs = 4_000L, port = broker.port),
            scope = scope,
        )

        repository.saveDevice(device)
    }

    @After
    fun tearDown() = runTest {
        broker.stop()
        deviceStore.clear()
        stateCache.clear()
    }

    @Test
    fun `refresh reads the machine into the cache the widget renders from`() = runTest {
        val result = repository.refreshState()

        assertTrue(result.isSuccess)
        val state = repository.getState()
        assertEquals(ConnectionStatus.ONLINE, state.connection)
        assertTrue(state.power)
        assertEquals(6, state.fanSpeed)
        assertEquals(21.05f, state.temperatureC!!, 0.01f)
    }

    @Test
    fun `a speed command is confirmed by the machine and kept`() = runTest {
        repository.refreshState()

        broker.replies = broker.replies + ("STATE-SET" to {
            """{"msg":"STATE-CHANGE","product-state":{"fpwr":["ON","ON"],"fnsp":["0006","0007"]}}"""
        })

        val result = repository.execute(DysonCommand.SetFanSpeed(7))

        assertTrue(result.isSuccess)
        assertEquals(7, repository.getState().fanSpeed)

        val sent = broker.received.map { it.second }.first { it.contains("STATE-SET") }
        assertTrue(sent.contains("\"fnsp\":\"0007\""))
    }

    @Test
    fun `power off is confirmed and reflected`() = runTest {
        repository.refreshState()
        broker.replies = broker.replies + ("STATE-SET" to {
            """{"msg":"STATE-CHANGE","product-state":{"fpwr":["ON","OFF"]}}"""
        })

        repository.setPower(false)

        assertFalse(repository.getState().power)
    }

    @Test
    fun `an unreachable machine rolls the optimistic value back`() = runTest {
        repository.refreshState()
        val before = repository.getState()
        assertEquals(6, before.fanSpeed)
        assertTrue(before.power)

        // The machine disappears between the tap and the send.
        broker.stop()

        val result = repository.execute(DysonCommand.SetFanSpeed(9))

        assertTrue("A failed command must report failure", result.isFailure)
        val after = repository.getState()
        assertEquals("The optimistic 9 must not stick", 6, after.fanSpeed)
        assertTrue(after.power)
        assertEquals(ConnectionStatus.OFFLINE, after.connection)
    }

    @Test
    fun `a command the model does not support changes nothing`() = runTest {
        repository.refreshState()
        val before = repository.getState()

        // A Purifier Cool has no heater.
        val result = repository.execute(DysonCommand.SetHeating(true))

        assertTrue(result.isSuccess)
        assertFalse(repository.getState().heating)
        assertEquals(before.fanSpeed, repository.getState().fanSpeed)
    }

    @Test
    fun `the optimistic preview paints before any network call`() = runTest {
        repository.refreshState()
        broker.stop()

        repository.previewCommand(DysonCommand.SetFanSpeed(9))

        val previewed = repository.getState()
        assertEquals("The widget should show the new value at once", 9, previewed.fanSpeed)
        assertEquals(ConnectionStatus.CONNECTING, previewed.connection)
    }

    @Test
    fun `a credential the store cannot keep is reported rather than swallowed`() = runTest {
        credentials.failWrites = true

        val failure = runCatching { repository.saveDevice(device) }

        assertTrue("Saving must not silently succeed", failure.isFailure)
    }

    @Test
    fun `diagnostics report a reachable machine`() = runTest {
        val report = repository.diagnose()

        assertTrue(report.configured)
        assertTrue(report.credentialStored)
        assertTrue(report.reachable)
        assertEquals("127.0.0.1", report.resolvedHost)
    }

    @Test
    fun `diagnostics name the problem when the machine is gone`() = runTest {
        broker.stop()

        val report = repository.diagnose()

        assertTrue(report.configured)
        assertTrue(report.credentialStored)
        assertFalse(report.reachable)
        assertTrue(report.summary.isNotBlank())
    }
}
