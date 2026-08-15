package com.radardeal.app.core

import android.util.Log
import com.radardeal.app.BuildConfig

/**
 * Logging façade for RadarDeal.
 *
 * Verbose logging is compiled in for debug builds only ([BuildConfig.VERBOSE_LOGGING]); release
 * builds keep nothing but [error], and even that never receives user content. Nothing here is
 * allowed to log a cookie, a session token, a Vinted URL containing credentials or any other
 * personal data — the monitoring code logs counts and status codes, not payloads.
 */
object RdLog {

    private const val TAG = "RadarDeal"

    val verbose: Boolean get() = BuildConfig.VERBOSE_LOGGING

    fun d(area: String, message: String) {
        if (verbose) Log.d(TAG, "[$area] $message")
    }

    fun i(area: String, message: String) {
        if (verbose) Log.i(TAG, "[$area] $message")
    }

    fun w(area: String, message: String, throwable: Throwable? = null) {
        if (verbose) Log.w(TAG, "[$area] $message", throwable)
    }

    /** Always compiled in: an unexpected failure is worth a line even in release. */
    fun e(area: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$area] $message", throwable)
    }
}
