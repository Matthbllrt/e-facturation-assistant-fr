package com.glasscontrol.dyson.data.local

import com.glasscontrol.dyson.domain.model.AirQuality
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads here mirror what real machines publish on `status/current`. */
class DysonStateParserTest {

    private val pureCool = DysonStateParser(DysonFamily.PURE_COOL)
    private val link = DysonStateParser(DysonFamily.PURE_COOL_LINK)
    private val hotCool = DysonStateParser(DysonFamily.HOT_COOL)

    @Test
    fun `parses a CURRENT-STATE payload from a Purifier Cool`() {
        val payload = """
            {"msg":"CURRENT-STATE","product-state":{
              "fpwr":"ON","fnsp":"0007","auto":"OFF","oson":"OION","nmod":"OFF",
              "fnst":"FAN","fdir":"ON","rhtm":"ON","hflr":"0082","cflr":"0091","ercd":"NONE"}}
        """.trimIndent()

        val result = pureCool.parse(payload, DysonState())

        assertTrue(result.handled)
        val state = result.state
        assertTrue(state.power)
        assertEquals(7, state.fanSpeed)
        assertFalse(state.autoMode)
        assertTrue("OION must be read as oscillating", state.oscillation)
        assertTrue(state.fanRunning)
        assertTrue(state.frontAirflow)
        assertEquals(82, state.hepaFilterPercent)
        assertEquals(91, state.carbonFilterPercent)
        assertEquals(ConnectionStatus.ONLINE, state.connection)
        assertNull("NONE is not a real error code", state.errorCode)
    }

    @Test
    fun `reads the second element of a STATE-CHANGE pair`() {
        // STATE-CHANGE reports [previous, current]; only the current value counts.
        val payload = """
            {"msg":"STATE-CHANGE","product-state":{
              "fpwr":["OFF","ON"],"fnsp":["0003","0009"],"oson":["OIOF","OION"]}}
        """.trimIndent()

        val state = pureCool.parse(payload, DysonState()).state

        assertTrue(state.power)
        assertEquals(9, state.fanSpeed)
        assertTrue(state.oscillation)
    }

    @Test
    fun `treats fnsp AUTO as an unknown numeric speed`() {
        val payload = """{"msg":"CURRENT-STATE","product-state":{"fpwr":"ON","fnsp":"AUTO","auto":"ON"}}"""

        val state = pureCool.parse(payload, DysonState(fanSpeed = 5)).state

        assertTrue(state.autoMode)
        assertNull("In auto the machine picks the speed itself", state.fanSpeed)
    }

    @Test
    fun `derives power and auto from fmod on Link machines`() {
        val auto = link.parse(
            """{"msg":"CURRENT-STATE","product-state":{"fmod":"AUTO","fnsp":"0004"}}""",
            DysonState(),
        ).state
        assertTrue(auto.power)
        assertTrue(auto.autoMode)

        val off = link.parse(
            """{"msg":"CURRENT-STATE","product-state":{"fmod":"OFF"}}""",
            DysonState(),
        ).state
        assertFalse(off.power)
        assertFalse(off.autoMode)
    }

    @Test
    fun `converts heating fields from decikelvin to celsius`() {
        val payload = """
            {"msg":"CURRENT-STATE","product-state":{"fpwr":"ON","hmod":"HEAT","hmax":"2980","hsta":"HEAT"}}
        """.trimIndent()

        val state = hotCool.parse(payload, DysonState()).state

        assertTrue(state.heating)
        assertTrue(state.heatingActive)
        // 2980 decikelvin = 298.0 K = 24.85 °C
        assertEquals(24.85f, state.targetTemperatureC!!, 0.01f)
    }

    @Test
    fun `parses environmental sensor data`() {
        val payload = """
            {"msg":"ENVIRONMENTAL-CURRENT-SENSOR-DATA","data":{
              "tact":"2942","hact":"0047","p25r":"0004","p10r":"0006","va10":"0031","noxl":"0012"}}
        """.trimIndent()

        val state = pureCool.parse(payload, DysonState()).state

        assertEquals(21.05f, state.temperatureC!!, 0.01f)
        assertEquals(47, state.humidity)
        assertEquals(4, state.pm25)
        assertEquals(6, state.pm10)
        assertEquals(3.1f, state.voc!!, 0.001f)
        assertEquals(1.2f, state.no2!!, 0.001f)
    }

    @Test
    fun `maps sensor sentinels to no reading rather than a number`() {
        val payload = """
            {"msg":"ENVIRONMENTAL-CURRENT-SENSOR-DATA","data":{
              "tact":"OFF","hact":"INIT","p25r":"FAIL","p10r":"NONE"}}
        """.trimIndent()

        val state = pureCool.parse(payload, DysonState()).state

        assertNull(state.temperatureC)
        assertNull(state.humidity)
        assertNull(state.pm25)
        assertNull(state.pm10)
    }

    @Test
    fun `reports INV filters as not fitted`() {
        val payload = """{"msg":"CURRENT-STATE","product-state":{"fpwr":"ON","hflr":"0075","cflr":"INV"}}"""

        val state = pureCool.parse(payload, DysonState()).state

        assertEquals(75, state.hepaFilterPercent)
        assertNull("INV means no carbon filter is present", state.carbonFilterPercent)
    }

    @Test
    fun `state messages merge instead of replacing previous readings`() {
        val withSensors = pureCool.parse(
            """{"msg":"ENVIRONMENTAL-CURRENT-SENSOR-DATA","data":{"tact":"2942","p25r":"0004"}}""",
            DysonState(),
        ).state

        val merged = pureCool.parse(
            """{"msg":"CURRENT-STATE","product-state":{"fpwr":"ON","fnsp":"0002"}}""",
            withSensors,
        ).state

        assertTrue(merged.power)
        assertEquals(2, merged.fanSpeed)
        assertEquals("Sensor readings must survive a state message", 4, merged.pm25)
    }

    @Test
    fun `ignores unrelated messages and malformed payloads`() {
        val previous = DysonState(power = true)

        val unrelated = pureCool.parse("""{"msg":"STATE-SET"}""", previous)
        assertFalse(unrelated.handled)
        assertEquals(previous, unrelated.state)

        val malformed = pureCool.parse("not json at all", previous)
        assertFalse(malformed.handled)
        assertEquals(previous, malformed.state)
    }

    @Test
    fun `air quality reports the worst pollutant band`() {
        val payload = """
            {"msg":"ENVIRONMENTAL-CURRENT-SENSOR-DATA","data":{"p25r":"0004","p10r":"0006","noxl":"0090"}}
        """.trimIndent()

        val state = pureCool.parse(payload, DysonState()).state

        // PM readings are good, but a NO2 index of 9 is not.
        assertEquals(AirQuality.VERY_POOR, state.airQuality)
    }

    @Test
    fun `air quality is unknown without any reading`() {
        assertEquals(AirQuality.UNKNOWN, DysonState().airQuality)
    }
}
