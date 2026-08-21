package app.mosaic.privatevault.domain.cooldown

import app.mosaic.privatevault.domain.model.Cooldown

/**
 * Turns "last activity + cooldown" into what the conversation screen shows.
 *
 * Note what this deliberately does not do: it never claims a thread was removed
 * from Instagram. Meta's API exposes no delete-conversation operation, so the
 * end state of a cooldown is an offer to help the user do it themselves, not a
 * silent remote action. See docs/INSTAGRAM.md.
 */
object CooldownPolicy {

    sealed interface State {
        /** Cooldown is off, or there is nothing to clean up yet. */
        data object Idle : State

        data class Waiting(val remainingMillis: Long) : State

        /** The delay has elapsed: show the discreet "Nettoyage prêt" affordance. */
        data object Ready : State
    }

    fun readyAt(lastActivityMillis: Long?, cooldown: Cooldown): Long? {
        if (cooldown.isOff || lastActivityMillis == null) return null
        return lastActivityMillis + cooldown.millis
    }

    fun state(nowMillis: Long, lastActivityMillis: Long?, cooldown: Cooldown): State {
        val readyAt = readyAt(lastActivityMillis, cooldown) ?: return State.Idle
        val remaining = readyAt - nowMillis
        return if (remaining <= 0L) State.Ready else State.Waiting(remaining)
    }

    /** Delay before the deferred worker should wake up, clamped to a sane floor. */
    fun scheduleDelayMillis(nowMillis: Long, lastActivityMillis: Long?, cooldown: Cooldown): Long? {
        val readyAt = readyAt(lastActivityMillis, cooldown) ?: return null
        return (readyAt - nowMillis).coerceAtLeast(0L)
    }
}
