package com.glasscontrol.dyson.data.local

import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandEncoderTest {

    private val pureCool = CommandEncoder(DysonFamily.PURE_COOL)
    private val link = CommandEncoder(DysonFamily.PURE_COOL_LINK)
    private val hotCool = CommandEncoder(DysonFamily.HOT_COOL)
    private val bigQuiet = CommandEncoder(DysonFamily.BIG_QUIET)

    @Test
    fun `encodes power with fpwr on modern machines`() {
        assertEquals(
            mapOf("fpwr" to "ON"),
            pureCool.encode(DysonCommand.SetPower(true), DysonState()),
        )
        assertEquals(
            mapOf("fpwr" to "OFF"),
            pureCool.encode(DysonCommand.SetPower(false), DysonState(power = true)),
        )
    }

    @Test
    fun `encodes power with fmod on Link machines and restores the preset`() {
        // Link machines have no separate power field: switching on must pick the
        // mode the machine was previously in.
        assertEquals(
            mapOf("fmod" to "AUTO"),
            link.encode(DysonCommand.SetPower(true), DysonState(autoMode = true)),
        )
        assertEquals(
            mapOf("fmod" to "FAN"),
            link.encode(DysonCommand.SetPower(true), DysonState(autoMode = false)),
        )
        assertEquals(
            mapOf("fmod" to "OFF"),
            link.encode(DysonCommand.SetPower(false), DysonState(power = true)),
        )
    }

    @Test
    fun `a manual speed powers on and leaves auto`() {
        assertEquals(
            mapOf("fpwr" to "ON", "auto" to "OFF", "fnsp" to "0006"),
            pureCool.encode(DysonCommand.SetFanSpeed(6), DysonState(autoMode = true)),
        )
    }

    @Test
    fun `speeds are zero padded to four characters`() {
        val data = pureCool.encode(DysonCommand.SetFanSpeed(3), DysonState())!!
        assertEquals("0003", data["fnsp"])

        val top = pureCool.encode(DysonCommand.SetFanSpeed(10), DysonState())!!
        assertEquals("0010", top["fnsp"])
    }

    @Test
    fun `speeds are clamped to the machine's range`() {
        assertEquals("0010", pureCool.encode(DysonCommand.SetFanSpeed(99), DysonState())!!["fnsp"])
        assertEquals("0001", pureCool.encode(DysonCommand.SetFanSpeed(0), DysonState())!!["fnsp"])
        assertEquals("0001", pureCool.encode(DysonCommand.SetFanSpeed(-5), DysonState())!!["fnsp"])
    }

    @Test
    fun `stepping past a boundary stays at the boundary`() {
        val atTop = pureCool.encode(DysonCommand.StepFanSpeed(1), DysonState(fanSpeed = 10))!!
        assertEquals("0010", atTop["fnsp"])

        val atBottom = pureCool.encode(DysonCommand.StepFanSpeed(-1), DysonState(fanSpeed = 1))!!
        assertEquals("0001", atBottom["fnsp"])
    }

    @Test
    fun `stepping from auto starts at a usable mid speed`() {
        // In auto there is no numeric speed to step from; jumping to 1 would feel wrong.
        val up = pureCool.encode(DysonCommand.StepFanSpeed(1), DysonState(autoMode = true))!!
        assertEquals("0005", up["fnsp"])

        val down = pureCool.encode(DysonCommand.StepFanSpeed(-1), DysonState(autoMode = true))!!
        assertEquals("0004", down["fnsp"])
    }

    @Test
    fun `encodes auto through fmod on Link machines`() {
        assertEquals(mapOf("fmod" to "AUTO"), link.encode(DysonCommand.SetAutoMode(true), DysonState()))
        assertEquals(mapOf("fmod" to "FAN"), link.encode(DysonCommand.SetAutoMode(false), DysonState()))
    }

    @Test
    fun `enabling auto also powers the machine on`() {
        assertEquals(
            mapOf("fpwr" to "ON", "auto" to "ON"),
            pureCool.encode(DysonCommand.SetAutoMode(true), DysonState()),
        )
        assertEquals(
            mapOf("auto" to "OFF"),
            pureCool.encode(DysonCommand.SetAutoMode(false), DysonState(power = true)),
        )
    }

    @Test
    fun `converts a target temperature into the padded kelvin field`() {
        val data = hotCool.encode(DysonCommand.SetTargetTemperature(21f), DysonState())!!
        assertEquals("HEAT", data["hmod"])
        // 21 °C = 294.15 K -> 2942 decikelvin (rounded)
        assertEquals("2942", data["hmax"])
    }

    @Test
    fun `oscillation answers in the dialect the machine speaks`() {
        // A TP04-era machine reports OION/OIOF and silently ignores ON/OFF.
        val legacy = DysonState(power = true, oscillationRaw = "OIOF")
        val onLegacy = pureCool.encode(DysonCommand.SetOscillation(true), legacy)!!
        assertEquals("OION", onLegacy["oson"])

        val offLegacy = pureCool.encode(
            DysonCommand.SetOscillation(false),
            legacy.copy(oscillationRaw = "OION", oscillation = true),
        )!!
        assertEquals("OIOF", offLegacy["oson"])

        // A newer machine reports ON/OFF and must be answered the same way.
        val modern = DysonState(power = true, oscillationRaw = "OFF")
        assertEquals("ON", pureCool.encode(DysonCommand.SetOscillation(true), modern)!!["oson"])
        assertEquals(
            "OFF",
            pureCool.encode(DysonCommand.SetOscillation(false), modern)!!["oson"],
        )
    }

    @Test
    fun `enabling oscillation powers on and replays the machine's angle window`() {
        val state = DysonState(
            power = true,
            oscillationRaw = "OIOF",
            oscillationAngleLow = 90,
            oscillationAngleHigh = 270,
        )

        val data = pureCool.encode(DysonCommand.SetOscillation(true), state)!!

        assertEquals("ON", data["fpwr"])
        assertEquals("CUST", data["ancp"])
        assertEquals("0090", data["osal"])
        assertEquals("0270", data["osau"])
    }

    @Test
    fun `an unusable angle window is left out rather than sent as nonsense`() {
        // Angles closer than 30 degrees are rejected by the machine.
        val narrow = DysonState(
            power = true,
            oscillationAngleLow = 100,
            oscillationAngleHigh = 110,
        )

        val data = pureCool.encode(DysonCommand.SetOscillation(true), narrow)!!

        assertEquals("ON", data["oson"])
        assertNull(data["osal"])
        assertNull(data["ancp"])
    }

    @Test
    fun `unsupported commands encode to nothing instead of failing`() {
        assertNull(
            "A Pure Cool has no heater",
            pureCool.encode(DysonCommand.SetHeating(true), DysonState()),
        )
        assertNull(
            pureCool.encode(DysonCommand.SetTargetTemperature(22f), DysonState()),
        )
        assertNull(
            "Link machines have no airflow direction field",
            link.encode(DysonCommand.SetAirflowDirection(true), DysonState()),
        )
        assertNull(
            "Big+Quiet machines do not oscillate",
            bigQuiet.encode(DysonCommand.SetOscillation(true), DysonState()),
        )
    }

    @Test
    fun `predicts the state a command will produce`() {
        val off = DysonState(power = false, fanSpeed = 3)

        assertTrue(pureCool.predict(DysonCommand.SetPower(true), off).power)

        val auto = pureCool.predict(DysonCommand.SetAutoMode(true), off)
        assertTrue(auto.autoMode)
        assertTrue(auto.power)
        assertNull("Auto leaves the numeric speed unknown", auto.fanSpeed)

        val manual = pureCool.predict(DysonCommand.SetFanSpeed(8), auto)
        assertEquals(8, manual.fanSpeed)
        assertFalse(manual.autoMode)
    }

    @Test
    fun `formats mqtt timestamps as UTC to the second`() {
        // 2026-08-17T20:15:00Z
        assertEquals("2026-08-17T20:15:00Z", CommandEncoder.mqttTime(1786997700000L))
    }
}
