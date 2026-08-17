package com.glasscontrol.dyson.data.local

/** Raw MQTT field names. Kept in one place so no other file hardcodes a key. */
internal object F {
    // product-state
    const val POWER = "fpwr"          // ON / OFF (post-Link machines)
    const val FAN_MODE = "fmod"       // FAN / AUTO / OFF (Link machines)
    const val FAN_STATE = "fnst"      // FAN / OFF — motor actually spinning
    const val FAN_SPEED = "fnsp"      // "0001".."0010" or "AUTO"
    const val AUTO = "auto"           // ON / OFF
    const val OSCILLATION = "oson"    // ON/OFF or OION/OIOF
    const val OSC_STATUS = "oscs"
    const val OSC_LOW = "osal"
    const val OSC_HIGH = "osau"
    const val OSC_ANGLE_PRESET = "ancp"
    const val NIGHT_MODE = "nmod"
    const val NIGHT_SPEED = "nmdv"
    const val AIRFLOW_DIRECTION = "fdir"   // ON = front
    const val HEAT_MODE = "hmod"      // HEAT / OFF
    const val HEAT_TARGET = "hmax"    // Kelvin x10, zero-padded to 4
    const val HEAT_STATE = "hsta"     // HEAT while actively heating
    const val FOCUS_MODE = "ffoc"
    const val HEPA_FILTER = "hflr"    // percent
    const val CARBON_FILTER = "cflr"  // percent, "INV" when absent
    const val FILTER_HOURS = "filf"   // hours remaining (Link machines)
    const val MONITORING = "rhtm"
    const val ERROR_CODE = "ercd"

    // environmental data
    const val TEMPERATURE = "tact"    // Kelvin x10
    const val HUMIDITY = "hact"
    const val PM25 = "p25r"
    const val PM25_ALT = "pm25"
    const val PM10 = "p10r"
    const val PM10_ALT = "pm10"
    const val VOC = "va10"            // index x10
    const val NO2 = "noxl"            // index x10
    const val PARTICULATES_LINK = "pact"
    const val VOC_LINK = "vact"

    // message envelope
    const val MSG = "msg"
    const val TIME = "time"
    const val MODE_REASON = "mode-reason"
    const val DATA = "data"
    const val PRODUCT_STATE = "product-state"

    const val MSG_CURRENT_STATE = "CURRENT-STATE"
    const val MSG_STATE_CHANGE = "STATE-CHANGE"
    const val MSG_ENVIRONMENTAL = "ENVIRONMENTAL-CURRENT-SENSOR-DATA"
    const val MSG_REQUEST_STATE = "REQUEST-CURRENT-STATE"
    const val MSG_REQUEST_ENVIRONMENTAL = "REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA"
    const val MSG_STATE_SET = "STATE-SET"

    const val ON = "ON"
    const val OFF = "OFF"
}
