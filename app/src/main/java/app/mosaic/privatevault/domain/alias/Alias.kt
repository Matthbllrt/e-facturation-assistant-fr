package app.mosaic.privatevault.domain.alias

import app.mosaic.privatevault.domain.model.MosaicSettings
import app.mosaic.privatevault.domain.model.TargetIdentity

/**
 * The alias is the whole privacy story of the UI: the real Instagram identity
 * exists in the vault, but nothing user-visible outside the settings reveal
 * flow is allowed to render it.
 */
object Alias {

    const val MAX_LENGTH = 32

    /** What the conversation header, notifications and recents label may show. */
    fun displayName(settings: MosaicSettings): String =
        settings.alias.trim().ifEmpty { MosaicSettings.DEFAULT_ALIAS }

    fun sanitize(input: String): String =
        input.replace(CONTROL_CHARS, "").trim().take(MAX_LENGTH)

    fun isValid(input: String): Boolean = sanitize(input).isNotEmpty()

    /**
     * Guard used by the UI layer: returns true when a string that is about to be
     * displayed, notified or exported still contains the real identity. Tests
     * assert this never fires on any user-facing surface.
     */
    fun leaksIdentity(rendered: String, identity: TargetIdentity?): Boolean {
        if (identity == null) return false
        val haystack = rendered.lowercase()
        val handle = identity.handle?.removePrefix("@")?.trim()?.lowercase()
        if (!handle.isNullOrEmpty() && haystack.contains(handle)) return true
        val name = identity.displayName.trim().lowercase()
        return name.isNotEmpty() && haystack.contains(name)
    }

    /**
     * Deterministic abstract avatar seed derived from the alias, so the avatar
     * is stable without storing anything derived from the real identity.
     */
    fun avatarSeed(alias: String): Int = alias.fold(7) { acc, c -> acc * 31 + c.code }

    private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\p{Cf}]")
}
