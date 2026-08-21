package app.mosaic.privatevault.security

import app.mosaic.privatevault.core.crypto.PinHasher
import app.mosaic.privatevault.core.time.Clock
import app.mosaic.privatevault.data.secure.SecureStore

/**
 * The fallback PIN, plus the throttling that makes a 6-digit secret defensible.
 *
 * Failures are counted in the encrypted store rather than in memory, so killing
 * the app is not a way to reset the penalty.
 */
class PinManager(
    private val store: SecureStore,
    private val clock: Clock = Clock.System,
) {

    sealed interface Result {
        data object Success : Result
        data class Wrong(val remainingAttempts: Int) : Result
        data class LockedOut(val retryInMillis: Long) : Result
        data object NotConfigured : Result
    }

    fun isConfigured(): Boolean = store.contains(SecureStore.KEY_PIN_VERIFIER)

    fun setPin(pin: CharArray): Boolean {
        if (!PinHasher.isAcceptable(pin)) return false
        store.putString(SecureStore.KEY_PIN_VERIFIER, PinHasher.hash(pin))
        resetFailures()
        return true
    }

    fun clear() {
        store.remove(SecureStore.KEY_PIN_VERIFIER)
        resetFailures()
    }

    fun verify(pin: CharArray): Result {
        val stored = store.getString(SecureStore.KEY_PIN_VERIFIER) ?: return Result.NotConfigured

        val penalty = remainingLockoutMillis()
        if (penalty > 0L) return Result.LockedOut(penalty)

        if (PinHasher.verify(pin, stored)) {
            resetFailures()
            return Result.Success
        }

        val failures = failureCount() + 1
        store.putString(KEY_FAILURES, failures.toString())
        store.putString(KEY_LAST_FAILURE, clock.nowMillis().toString())

        val lockout = lockoutMillisFor(failures)
        return if (lockout > 0L) Result.LockedOut(lockout)
        else Result.Wrong(remainingAttempts = (FREE_ATTEMPTS - failures).coerceAtLeast(0))
    }

    fun remainingLockoutMillis(): Long {
        val failures = failureCount()
        val penalty = lockoutMillisFor(failures)
        if (penalty <= 0L) return 0L
        val last = store.getString(KEY_LAST_FAILURE)?.toLongOrNull() ?: return 0L
        return (last + penalty - clock.nowMillis()).coerceAtLeast(0L)
    }

    fun failureCount(): Int = store.getString(KEY_FAILURES)?.toIntOrNull() ?: 0

    private fun resetFailures() {
        store.remove(KEY_FAILURES)
        store.remove(KEY_LAST_FAILURE)
    }

    /**
     * Exponential from the 4th failure, capped at 15 minutes. Mosaic never
     * self-wipes on repeated failures: an accidental lockout must not destroy
     * messages that exist nowhere else.
     */
    fun lockoutMillisFor(failures: Int): Long = when {
        failures < FREE_ATTEMPTS -> 0L
        else -> {
            val steps = (failures - FREE_ATTEMPTS).coerceAtMost(6)
            (BASE_LOCKOUT_MILLIS shl steps).coerceAtMost(MAX_LOCKOUT_MILLIS)
        }
    }

    companion object {
        const val FREE_ATTEMPTS = 3
        const val BASE_LOCKOUT_MILLIS = 15_000L
        const val MAX_LOCKOUT_MILLIS = 15 * 60_000L
        private const val KEY_FAILURES = "lock.pin_failures"
        private const val KEY_LAST_FAILURE = "lock.pin_last_failure"
    }
}
