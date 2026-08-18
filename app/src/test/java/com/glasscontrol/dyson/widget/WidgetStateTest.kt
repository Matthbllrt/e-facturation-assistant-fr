package com.glasscontrol.dyson.widget

import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme
import com.glasscontrol.dyson.domain.CapabilityResolver
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a machine's state becomes what the home screen draws. */
class WidgetStateTest {

    private val device = DysonDevice(
        serial = "NN2-EU-ABC1234A",
        name = "Salon",
        deviceType = "438K",
        credential = "secret",
        model = "TP07",
    )

    private val capabilities = CapabilityResolver.resolve(DysonFamily.PURE_COOL, null)

    private fun ui(state: DysonState, config: WidgetConfig = WidgetConfig()) =
        WidgetUiState.from(device, state, capabilities, config)

    // ---- Status machine

    @Test
    fun `a reachable running machine is online and on`() {
        val state = ui(DysonState(connection = ConnectionStatus.ONLINE, power = true, fanSpeed = 6))

        assertEquals(WidgetStatus.ONLINE_ON, state.status)
        assertTrue(state.isOnline)
        assertEquals("Online", state.statusLabel)
    }

    @Test
    fun `a reachable idle machine is online and off`() {
        val state = ui(DysonState(connection = ConnectionStatus.ONLINE, power = false))

        assertEquals(WidgetStatus.ONLINE_OFF, state.status)
        assertTrue(state.isOnline)
    }

    @Test
    fun `an unreachable machine is offline and keeps its last known values`() {
        val state = ui(
            DysonState(
                connection = ConnectionStatus.OFFLINE,
                power = true,
                fanSpeed = 6,
                lastUpdatedEpochMs = 1_700_000_000_000L,
            )
        )

        assertEquals(WidgetStatus.OFFLINE, state.status)
        assertFalse(state.isOnline)
        assertEquals("Offline", state.statusLabel)
        // The controls stay meaningful rather than blanking out.
        assertEquals("06", state.speedLabel)
        assertTrue(state.power)
    }

    @Test
    fun `a command in flight reads as refreshing, not as a fresh connection`() {
        val state = ui(
            DysonState(connection = ConnectionStatus.CONNECTING, power = true, lastUpdatedEpochMs = 1L)
        )

        assertEquals(WidgetStatus.REFRESHING, state.status)
        assertTrue("A refresh is still a working connection", state.isOnline)
    }

    @Test
    fun `nothing read yet reads as connecting`() {
        val neverRead = ui(DysonState(connection = ConnectionStatus.CONNECTING))
        assertEquals(WidgetStatus.CONNECTING, neverRead.status)

        val unknown = ui(DysonState(connection = ConnectionStatus.UNKNOWN))
        assertEquals(WidgetStatus.CONNECTING, unknown.status)
    }

    @Test
    fun `a fault reported by the machine surfaces as an error`() {
        val state = ui(
            DysonState(connection = ConnectionStatus.ONLINE, power = true, errorCode = "E01")
        )
        assertEquals(WidgetStatus.ERROR, state.status)
    }

    @Test
    fun `being unreachable outranks a stale fault code`() {
        val state = ui(DysonState(connection = ConnectionStatus.OFFLINE, errorCode = "E01"))
        assertEquals(WidgetStatus.OFFLINE, state.status)
    }

    @Test
    fun `an unconfigured widget says so instead of pretending`() {
        val state = WidgetUiState.from(null, DysonState(), capabilities, WidgetConfig())

        assertEquals(WidgetStatus.NOT_CONFIGURED, state.status)
        assertFalse(state.configured)
    }

    // ---- Speed presentation

    @Test
    fun `speed is padded to two digits across the whole range`() {
        assertEquals(
            "01",
            ui(DysonState(connection = ConnectionStatus.ONLINE, power = true, fanSpeed = 1)).speedLabel,
        )
        assertEquals(
            "10",
            ui(DysonState(connection = ConnectionStatus.ONLINE, power = true, fanSpeed = 10)).speedLabel,
        )
    }

    @Test
    fun `auto and off have their own readings rather than a misleading number`() {
        val auto = ui(
            DysonState(connection = ConnectionStatus.ONLINE, power = true, autoMode = true, fanSpeed = null)
        )
        assertEquals("AU", auto.speedLabel)

        val off = ui(DysonState(connection = ConnectionStatus.ONLINE, power = false, fanSpeed = 6))
        assertEquals("--", off.speedLabel)
    }

    // ---- Naming

    @Test
    fun `the badge shows a short model code, not a marketing range name`() {
        assertEquals("TP07", ui(DysonState()).model)

        // Without a code from the account, the range's representative one stands in.
        val noModel = WidgetUiState.from(
            device.copy(model = null), DysonState(), capabilities, WidgetConfig(),
        )
        assertEquals("TP04", noModel.model)
        assertEquals("TP02", DysonFamily.shortCode("475"))
        assertEquals("HP04", DysonFamily.shortCode("527M"))
    }

    @Test
    fun `a per-widget custom name overrides the device name`() {
        val named = ui(DysonState(), WidgetConfig(customName = "Chambre"))
        assertEquals("Chambre", named.deviceName)

        val blank = ui(DysonState(), WidgetConfig(customName = "  "))
        assertEquals("Salon", blank.deviceName)
    }

    // ---- Capabilities drive what is drawn

    @Test
    fun `controls the machine lacks are not offered`() {
        val bigQuiet = CapabilityResolver.resolve(DysonFamily.BIG_QUIET, null)
        val state = WidgetUiState.from(device, DysonState(), bigQuiet, WidgetConfig())

        assertFalse("Big+Quiet machines do not oscillate", state.capabilities.oscillation)
        assertTrue(state.capabilities.power)
        assertTrue(state.capabilities.fanSpeed)
    }

    @Test
    fun `an unknown model offers only what is safe to assume`() {
        val unknown = CapabilityResolver.resolve(DysonFamily.UNSUPPORTED, null)
        val state = WidgetUiState.from(device, DysonState(), unknown, WidgetConfig())

        assertTrue(state.capabilities.power)
        assertFalse(state.capabilities.fanSpeed)
        assertFalse(state.capabilities.autoMode)
        assertFalse(state.capabilities.oscillation)
    }

    // ---- Taps

    @Test
    fun `taps resolve to absolute commands so a later replay cannot undo them`() {
        assertEquals(
            DysonCommand.SetPower(true),
            WidgetCommands.toCommand(WidgetCommands.TOGGLE_POWER, DysonState(power = false)),
        )
        assertEquals(
            DysonCommand.SetPower(false),
            WidgetCommands.toCommand(WidgetCommands.TOGGLE_POWER, DysonState(power = true)),
        )
        assertEquals(
            DysonCommand.SetAutoMode(true),
            WidgetCommands.toCommand(WidgetCommands.TOGGLE_AUTO, DysonState(autoMode = false)),
        )
        assertEquals(
            DysonCommand.SetOscillation(true),
            WidgetCommands.toCommand(WidgetCommands.TOGGLE_OSCILLATION, DysonState(oscillation = false)),
        )
    }

    @Test
    fun `speed taps clamp at both ends of the range`() {
        assertEquals(
            DysonCommand.SetFanSpeed(7),
            WidgetCommands.toCommand(WidgetCommands.SPEED_UP, DysonState(fanSpeed = 6)),
        )
        assertEquals(
            DysonCommand.SetFanSpeed(10),
            WidgetCommands.toCommand(WidgetCommands.SPEED_UP, DysonState(fanSpeed = 10)),
        )
        assertEquals(
            DysonCommand.SetFanSpeed(1),
            WidgetCommands.toCommand(WidgetCommands.SPEED_DOWN, DysonState(fanSpeed = 1)),
        )
    }

    @Test
    fun `refresh is not a state change`() {
        assertNull(WidgetCommands.toCommand(WidgetCommands.REFRESH, DysonState()))
    }

    @Test
    fun `the default widget config is usable out of the box`() {
        val config = WidgetConfig()
        assertEquals(WidgetTheme.AUTO, config.theme)
        assertNull(config.customName)
    }
}
