package com.glasscontrol.dyson.core

import android.util.Log

/**
 * Logging helpers that make it hard to accidentally leak a credential.
 *
 * Nothing in this app ever logs a raw MQTT password, bearer token or OTP: values
 * that could identify or authenticate the user go through [redact] first.
 */
object Redact {

    /** Keeps only the shape of a secret so logs stay useful for debugging. */
    fun redact(secret: String?): String = when {
        secret == null -> "null"
        secret.isEmpty() -> "empty"
        else -> "***(${secret.length})"
    }

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

internal fun logD(message: String) {
    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
}

internal fun logI(message: String) = Log.i(TAG, message).let { }

internal fun logW(message: String, error: Throwable? = null) {
    if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
}
