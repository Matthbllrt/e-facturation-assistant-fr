package com.glasscontrol.dyson.data.store

import androidx.test.core.app.ApplicationProvider
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val device = DysonDevice(
        serial = "NN2-EU-ABC1234A",
        name = "Salon",
        deviceType = "438K",
        credential = "super-secret-credential",
        host = "192.168.1.42",
        model = "TP07",
    )

    @Test
    fun `device round trips without ever persisting the credential`() = runTest {
        val store = DeviceConfigStore(context)
        store.save(device)

        val loaded = store.device.first()
        assertNotNull(loaded)
        assertEquals("NN2-EU-ABC1234A", loaded!!.serial)
        assertEquals("Salon", loaded.name)
        assertEquals("438K", loaded.deviceType)
        assertEquals("192.168.1.42", loaded.host)
        // The secret belongs in the Keystore-backed store, never in DataStore.
        assertEquals("", loaded.credential)

        store.clear()
        assertNull(store.device.first())
    }

    @Test
    fun `updating the host keeps the rest of the record`() = runTest {
        val store = DeviceConfigStore(context)
        store.save(device)

        store.updateHost("192.168.1.99")

        val loaded = store.device.first()!!
        assertEquals("192.168.1.99", loaded.host)
        assertEquals("Salon", loaded.name)

        store.clear()
    }

    @Test
    fun `state cache round trips every field the widget reads`() = runTest {
        val cache = StateCache(context)
        val state = DysonState(
            connection = ConnectionStatus.ONLINE,
            power = true,
            fanSpeed = 7,
            autoMode = false,
            oscillation = true,
            nightMode = false,
            temperatureC = 21.5f,
            humidity = 47,
            pm25 = 4,
            hepaFilterPercent = 82,
            lastUpdatedEpochMs = 1_700_000_000_000L,
        )

        cache.write(state)

        assertEquals(state, cache.read())

        cache.clear()
        assertEquals(DysonState(), cache.read())
    }

    @Test
    fun `widget configs are independent per instance`() = runTest {
        val store = WidgetConfigStore(context)

        val first = WidgetConfig(
            theme = WidgetTheme.DARK,
            glassOpacity = 0.2f,
            showSensors = false,
            quickControls = listOf(QuickControl.POWER),
        )
        val second = WidgetConfig(theme = WidgetTheme.LIGHT, glassOpacity = 0.9f)

        store.save(11, first)
        store.save(22, second)

        assertEquals(first, store.read(11))
        assertEquals(second, store.read(22))

        store.remove(11)
        // A removed widget falls back to defaults rather than failing.
        assertEquals(WidgetConfig(), store.read(11))
        assertEquals(second, store.read(22))

        store.remove(22)
    }

    @Test
    fun `an unconfigured widget gets sensible defaults`() = runTest {
        val config = WidgetConfigStore(context).read(9999)

        assertEquals(WidgetTheme.AUTO, config.theme)
        assertTrue(config.showSensors)
        assertTrue(config.quickControls.contains(QuickControl.POWER))
        assertFalse(config.quickControls.isEmpty())
    }
}
