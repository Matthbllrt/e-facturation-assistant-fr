package app.mosaic.privatevault.security

import app.mosaic.privatevault.core.time.Clock
import app.mosaic.privatevault.domain.model.AutoLockDelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the vault is currently open, and when it should close again.
 *
 * Kept free of Android types so the auto-lock rules can be tested directly.
 * The process-lifecycle wiring lives in `MosaicApp`.
 */
class AppLock(private val clock: Clock = Clock.System) {

    private val locked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = locked.asStateFlow()

    @Volatile
    private var lastInteractionMillis: Long = 0L

    @Volatile
    private var backgroundedAtMillis: Long? = null

    fun unlock() {
        locked.value = false
        lastInteractionMillis = clock.nowMillis()
        backgroundedAtMillis = null
    }

    /** The panic button, and the auto-lock outcome. Cheap and always safe. */
    fun lock() {
        locked.value = true
        backgroundedAtMillis = null
    }

    fun noteInteraction() {
        lastInteractionMillis = clock.nowMillis()
    }

    fun noteBackgrounded() {
        backgroundedAtMillis = clock.nowMillis()
    }

    /**
     * Re-evaluates the lock on return to the foreground.
     *
     * Time spent in the background counts fully: an app left in the recents
     * list for an hour must not come back unlocked, whatever the idle timer
     * thinks.
     */
    fun refreshOnForeground(delay: AutoLockDelay): Boolean {
        if (locked.value) return true
        if (delay == AutoLockDelay.NEVER) {
            backgroundedAtMillis = null
            return false
        }
        if (delay == AutoLockDelay.IMMEDIATE) {
            lock()
            return true
        }
        val since = backgroundedAtMillis ?: lastInteractionMillis
        if (clock.nowMillis() - since >= delay.millis) {
            lock()
            return true
        }
        backgroundedAtMillis = null
        return false
    }

    /** Idle check while the app is in the foreground. */
    fun refreshOnIdle(delay: AutoLockDelay): Boolean {
        if (locked.value) return true
        if (delay == AutoLockDelay.NEVER) return false
        if (clock.nowMillis() - lastInteractionMillis >= delay.millis) {
            lock()
            return true
        }
        return false
    }
}
