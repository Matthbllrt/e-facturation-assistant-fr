package com.glasscontrol.dyson.data.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builders for the three messages we ever publish. */
object MqttMessages {

    fun requestState(now: Long = System.currentTimeMillis()): String = buildJsonObject {
        put(F.MSG, F.MSG_REQUEST_STATE)
        put(F.TIME, CommandEncoder.mqttTime(now))
    }.toString()

    fun requestEnvironmental(now: Long = System.currentTimeMillis()): String = buildJsonObject {
        put(F.MSG, F.MSG_REQUEST_ENVIRONMENTAL)
        put(F.TIME, CommandEncoder.mqttTime(now))
    }.toString()

    /** `mode-reason: LAPP` marks the change as coming from a local app. */
    fun stateSet(data: Map<String, String>, now: Long = System.currentTimeMillis()): String =
        buildJsonObject {
            put(F.MSG, F.MSG_STATE_SET)
            put(F.TIME, CommandEncoder.mqttTime(now))
            put(F.MODE_REASON, "LAPP")
            put(F.DATA, JsonObject(data.mapValues { JsonPrimitive(it.value) }))
        }.toString()
}
