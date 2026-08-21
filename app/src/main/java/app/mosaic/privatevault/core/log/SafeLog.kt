package app.mosaic.privatevault.core.log

import android.util.Log

/**
 * The only logging entry point in Mosaic.
 *
 * Message bodies, Instagram handles, aliases, tokens and PINs must never reach
 * logcat: on a shared or forensically examined device logcat is readable by far
 * more actors than the encrypted vault is. Callers therefore log *events*, not
 * *content*, and anything that could still carry content goes through
 * [redact] first.
 *
 * In release builds the sink is disabled entirely.
 */
object SafeLog {

    /** Flipped off for release builds by [configure]; defaults to off. */
    @Volatile
    private var enabled: Boolean = false

    private const val TAG = "Mosaic"

    fun configure(debugBuild: Boolean) {
        enabled = debugBuild
    }

    fun d(event: String) {
        if (enabled) Log.d(TAG, event)
    }

    fun i(event: String) {
        if (enabled) Log.i(TAG, event)
    }

    fun w(event: String, error: Throwable? = null) {
        if (enabled) Log.w(TAG, event, error?.let { SanitizedThrowable(it) })
    }

    /**
     * Errors are the one level that survives into release builds, because a
     * silent failure in the capture path would look exactly like "no new
     * messages". Only the event name and the exception *type* are emitted.
     */
    fun e(event: String, error: Throwable? = null) {
        Log.e(TAG, event, error?.let { SanitizedThrowable(it) })
    }

    /**
     * Renders a value safe to log: a length and a short salted-free digest,
     * enough to correlate two occurrences of the same string across a session
     * without disclosing it.
     */
    fun redact(value: String?): String {
        if (value == null) return "<null>"
        if (value.isEmpty()) return "<empty>"
        return "<${value.length}ch:${fingerprint(value)}>"
    }

    private fun fingerprint(value: String): String {
        // FNV-1a, truncated. Deliberately not reversible in any practical sense
        // for message-sized inputs, and never persisted.
        var hash = -0x340d631b_7bdddcdbL // FNV-1a 64-bit offset basis
        for (char in value) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash).takeLast(6)
    }

    /**
     * Strips the message of a throwable before it is handed to logcat: SQLCipher
     * and Retrofit both like to quote offending values back at you.
     */
    private class SanitizedThrowable(original: Throwable) :
        Throwable(original::class.java.name, original.cause?.let { SanitizedThrowable(it) }) {
        init {
            stackTrace = original.stackTrace
        }
    }
}
