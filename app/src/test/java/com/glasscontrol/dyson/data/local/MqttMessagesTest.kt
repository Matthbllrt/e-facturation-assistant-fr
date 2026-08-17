package com.glasscontrol.dyson.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MqttMessagesTest {

    private val time = 1786997700000L // 2026-08-17T20:15:00Z

    private fun parse(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `builds a state request`() {
        val message = parse(MqttMessages.requestState(time))

        assertEquals("REQUEST-CURRENT-STATE", message["msg"]!!.jsonPrimitive.content)
        assertEquals("2026-08-17T20:15:00Z", message["time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `builds an environmental request`() {
        val message = parse(MqttMessages.requestEnvironmental(time))

        assertEquals(
            "REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA",
            message["msg"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `builds a STATE-SET with the local-app mode reason`() {
        val message = parse(MqttMessages.stateSet(mapOf("fpwr" to "ON", "fnsp" to "0004"), time))

        assertEquals("STATE-SET", message["msg"]!!.jsonPrimitive.content)
        assertEquals("2026-08-17T20:15:00Z", message["time"]!!.jsonPrimitive.content)
        // LAPP marks the change as coming from a local app rather than the machine.
        assertEquals("LAPP", message["mode-reason"]!!.jsonPrimitive.content)

        val data = message["data"]!!.jsonObject
        assertEquals("ON", data["fpwr"]!!.jsonPrimitive.content)
        // Values must stay strings: a bare 4 would be rejected by the machine.
        assertEquals("0004", data["fnsp"]!!.jsonPrimitive.content)
        assertEquals("\"0004\"", data["fnsp"].toString())
    }
}
