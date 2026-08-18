package com.glasscontrol.dyson.core

import android.util.Log
import com.glasscontrol.dyson.BuildConfig

/**
 * Logging helpers that make it hard to accidentally leak a credential.
 *
 * Nothing in this app ever logs a raw MQTT password, bearer token or OTP: values
 * that could identify or authenticate the user go through [redact] first.
 */
object Redact {

    /** Serial numbers are identifying; keep the model prefix only (e.g. "NN2-EU-…"). */
    fun serial(serial: String?): String {
        if (serial.isNullOrEmpty()) return "unknown"
        val head = serial.take(3)
        return "$head-…"
    }

    /** Local IPs are not secret, but we still avoid pinning a household in logs. */
    fun host(host: String?): String {
        if (host.isNullOrEmpty()) return "unknown"
        val parts = host.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.x.x" else "host"
    }
}

private const val TAG = "DysonGlass"

/**
 * Subsystem labels, so a logcat filter can follow one concern at a time.
 *
 * They are prefixes rather than separate tags because a single tag keeps
 * `adb logcat -s DysonGlass` useful for everything at once.
 */
object LogArea {
    const val CONNECTION = "[DysonConnection]"
    const val MQTT = "[DysonMQTT]"
    const val WIDGET_ACTION = "[WidgetAction]"
    const val WIDGET_UPDATE = "[WidgetUpdate]"
    const val STORAGE = "[DysonStorage]"
    const val CLOUD = "[DysonCloud]"
}

/**
 * Debug-only detail.
 *
 * Gated on [BuildConfig.DEBUG] as well as the loggable check, so a release build
 * cannot emit connection detail even if someone enables the tag.
 */
internal fun logD(area: String, message: String) {
    if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "$area $message")
}

internal fun logW(area: String, message: String, error: Throwable? = null) {
    if (error != null) Log.w(TAG, "$area $message", error) else Log.w(TAG, "$area $message")
}
