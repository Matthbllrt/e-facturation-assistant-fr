package app.mosaic.privatevault.domain.model

/**
 * The real Instagram identity behind the alias.
 *
 * Held apart from everything else on purpose: it lives only in the encrypted
 * [app.mosaic.privatevault.data.secure.SecureStore], it is never rendered on the
 * conversation screen, never used in a notification, and never used as the task
 * label in the recents switcher. Revealing it costs a fresh authentication.
 *
 * @param threadKey the notification `shortcutId`/conversation key captured when
 *   the user picked the person. Two different people can share a display name;
 *   this is what keeps them apart.
 */
data class TargetIdentity(
    val handle: String?,
    val displayName: String,
    val threadKey: String? = null,
) {
    val isConfigured: Boolean get() = displayName.isNotBlank() || !handle.isNullOrBlank()
}
