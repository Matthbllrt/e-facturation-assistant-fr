package app.mosaic.privatevault.domain.model

/** How Mosaic gets messages. Chosen once during setup, changeable later. */
enum class SyncMode {
    /** Not configured yet. */
    UNSET,

    /**
     * Instagram Graph API. Requires the user's own account to be a
     * professional (Business/Creator) account — Meta refuses to issue usable
     * messaging tokens for consumer accounts.
     */
    OFFICIAL_API,

    /**
     * Android NotificationListenerService. Works with any Instagram account,
     * captures only what arrives as a notification from now on, and never
     * touches Instagram credentials.
     */
    ANDROID_NOTIFICATIONS,
}

/**
 * Delay after which Mosaic surfaces the discreet "cleanup ready" hint.
 *
 * It never deletes anything on Instagram: no official API operation exists for
 * that (verified against Meta's Conversations API documentation), and the
 * alternatives — Accessibility click automation, private endpoints — are exactly
 * the account-killing tricks this app refuses to use. See docs/INSTAGRAM.md.
 */
@JvmInline
value class Cooldown(val seconds: Long) {
    val millis: Long get() = seconds * 1000L
    val isOff: Boolean get() = seconds <= 0L

    companion object {
        val Off = Cooldown(0)
        val Seconds30 = Cooldown(30)
        val Minutes2 = Cooldown(120)
        val Minutes5 = Cooldown(300)
        val Minutes15 = Cooldown(900)

        /** The fixed choices offered in settings; anything else is "personnalisé". */
        val presets: List<Cooldown> = listOf(Off, Seconds30, Minutes2, Minutes5, Minutes15)

        fun isPreset(cooldown: Cooldown): Boolean = cooldown in presets
    }
}

/** Idle delay before the vault re-locks itself. */
enum class AutoLockDelay(val millis: Long) {
    IMMEDIATE(0L),
    SECONDS_15(15_000L),
    MINUTE_1(60_000L),
    MINUTES_5(300_000L),
    NEVER(Long.MAX_VALUE),
}

/** What, if anything, Mosaic is allowed to put in the status bar. */
enum class NotificationPolicy {
    /** Default. Mosaic never posts a notification. */
    NONE,

    /** A content-free "1 nouvel élément" ping, no sender, no text, no preview. */
    NEUTRAL,
}

data class MosaicSettings(
    val syncMode: SyncMode = SyncMode.UNSET,
    val alias: String = DEFAULT_ALIAS,
    val cooldownSeconds: Long = 0L,
    val notificationPolicy: NotificationPolicy = NotificationPolicy.NONE,
    val autoLock: AutoLockDelay = AutoLockDelay.MINUTE_1,
    val screenshotProtection: Boolean = true,
    val onboardingComplete: Boolean = false,
    val avatarSeed: Int = 0,
) {
    val cooldown: Cooldown get() = Cooldown(cooldownSeconds)

    companion object {
        const val DEFAULT_ALIAS = "Louis"
    }
}
