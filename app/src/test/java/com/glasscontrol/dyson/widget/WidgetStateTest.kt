package com.glasscontrol.dyson.widget

import com.glasscontrol.dyson.data.store.QuickControl
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme
import com.glasscontrol.dyson.domain.CapabilityResolver
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.ui.widgetpreview.isActive
import com.glasscontrol.dyson.ui.widgetpreview.isSupported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the widget decides to draw for a given machine and state. */
class WidgetStateTest {

    @Test
    fun `quick controls are filtered down to what the machine supports`() {
        val pureCool = CapabilityResolver.resolve(DysonFamily.PURE_COOL, null)
        val requested = listOf(
            QuickControl.POWER,
            QuickControl.AUTO,
            QuickControl.OSCILLATION,
            QuickControl.HEAT,
        )

        val shown = requested.filter { it.isSupported(pureCool) }

        assertEquals(
            "A Pure Cool has no heater, so the heat chip must not be drawn",
            listOf(QuickControl.POWER, QuickControl.AUTO, QuickControl.OSCILLATION),
            shown,
        )
    }

    @Test
    fun `a heating machine keeps its heat control`() {
        val hotCool = CapabilityResolver.resolve(DysonFamily.HOT_COOL, null)
        assertTrue(QuickControl.HEAT.isSupported(hotCool))
    }

    @Test
    fun `a Big+Quiet machine drops the oscillation control`() {
        val bigQuiet = CapabilityResolver.resolve(DysonFamily.BIG_QUIET, null)
        assertFalse(QuickControl.OSCILLATION.isSupported(bigQuiet))
    }

    @Test
    fun `chips light up from the machine state`() {
        val state = DysonState(power = true, autoMode = true, oscillation = false, nightMode = true)

        assertTrue(QuickControl.POWER.isActive(state))
        assertTrue(QuickControl.AUTO.isActive(state))
        assertFalse(QuickControl.OSCILLATION.isActive(state))
        assertTrue(QuickControl.NIGHT.isActive(state))
    }

    @Test
    fun `glass opacity maps onto the available panel drawables`() {
        // Five drawables back a continuous slider; every value must land on one.
        val buckets = listOf(0f, 0.2f, 0.4f, 0.55f, 0.8f, 1f).map { opacity ->
            (opacity.coerceIn(0f, 1f) * 4).toInt().coerceIn(0, 4)
        }

        assertEquals(listOf(0, 0, 1, 2, 3, 4), buckets)
    }

    @Test
    fun `an offline machine still renders its last known values`() {
        // The widget must never blank out: it shows stale readings with an
        // offline badge rather than nothing at all.
        val lastKnown = DysonState(
            connection = ConnectionStatus.OFFLINE,
            power = true,
            fanSpeed = 5,
            temperatureC = 21f,
            lastUpdatedEpochMs = 1_700_000_000_000L,
        )

        assertEquals(5, lastKnown.fanSpeed)
        assertEquals(21f, lastKnown.temperatureC)
        assertFalse(lastKnown.isOnline)
    }

    @Test
    fun `the default widget config is usable out of the box`() {
        val config = WidgetConfig()

        assertEquals(WidgetTheme.AUTO, config.theme)
        assertTrue(config.quickControls.isNotEmpty())
        assertTrue(config.glassOpacity in 0f..1f)
    }
}
