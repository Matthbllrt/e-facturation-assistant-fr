package com.radardeal.app.domain.model

/**
 * A "veille" — one saved Vinted search that RadarDeal polls on a fixed interval.
 *
 * A watch is defined either by simple criteria ([keyword] / [brand] / [maxPrice]) or by a raw
 * Vinted search [sourceUrl] pasted by the user. The URL form is the richer one: it preserves
 * every advanced filter (catégorie, taille, état, couleur…) that Vinted itself encoded.
 */
data class Watch(
    val id: Long = 0L,
    val name: String,
    val keyword: String? = null,
    val brand: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val sourceUrl: String? = null,
    val intervalSeconds: Int = ScanFrequency.FAST.seconds,
    val isActive: Boolean = true,
    val notifyEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastScanAt: Long? = null,
    val lastScanStatus: ScanStatus = ScanStatus.NEVER,
    /**
     * False until the very first successful scan has stored its results. The baseline scan
     * records what already exists without flagging any of it as NEW — otherwise creating a
     * watch would immediately fire 50 notifications.
     */
    val baselineDone: Boolean = false,
) {
    /** True when this watch was created by pasting a Vinted search URL. */
    val isUrlBased: Boolean get() = !sourceUrl.isNullOrBlank()

    /** Short human summary shown under the watch name, e.g. "≤ 80 € · 30 sec". */
    fun criteriaSummary(): String {
        val parts = mutableListOf<String>()
        if (isUrlBased) {
            parts += "Recherche Vinted"
        } else {
            brand?.takeIf { it.isNotBlank() }?.let { parts += it }
            keyword?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        maxPrice?.let { parts += "≤ ${com.radardeal.app.core.Formatters.price(it)}" }
        return parts.joinToString(" · ").ifBlank { "Tous les résultats" }
    }
}

/** Preset polling intervals offered in the UI. */
enum class ScanFrequency(val seconds: Int, val label: String, val description: String) {
    TURBO(15, "Turbo", "15 secondes"),
    FAST(30, "Rapide", "30 secondes"),
    STANDARD(60, "Standard", "1 minute"),
    ECO(120, "Éco", "2 minutes");

    companion object {
        fun fromSeconds(seconds: Int): ScanFrequency =
            entries.minByOrNull { kotlin.math.abs(it.seconds - seconds) } ?: FAST
    }
}

/**
 * Outcome of the last scan for a watch. Every value maps to an explicit, non-technical state
 * shown in the UI — the app never silently swallows a failure.
 */
enum class ScanStatus {
    /** No scan has run yet. */
    NEVER,

    /** Scan completed and results were parsed. */
    OK,

    /** Scan completed but Vinted returned zero results for these criteria. */
    EMPTY,

    /** No usable Vinted session: the user has to sign in inside RadarDeal's WebView. */
    NEEDS_LOGIN,

    /** Vinted is asking the user to complete a verification (captcha / challenge page). */
    NEEDS_VERIFICATION,

    /** Vinted asked us to slow down. The scheduler backs off automatically. */
    RATE_LIMITED,

    /** Device offline, DNS failure, timeout… */
    NETWORK_ERROR,

    /** Reached Vinted, but the response could not be understood. */
    PARSE_ERROR;

    val isProblem: Boolean
        get() = this == NEEDS_LOGIN || this == NEEDS_VERIFICATION ||
            this == RATE_LIMITED || this == NETWORK_ERROR || this == PARSE_ERROR

    /** Short label shown on the watch card. */
    val label: String
        get() = when (this) {
            NEVER -> "Jamais scanné"
            OK -> "À jour"
            EMPTY -> "Aucun résultat"
            NEEDS_LOGIN -> "Connexion Vinted nécessaire"
            NEEDS_VERIFICATION -> "Vérification Vinted nécessaire"
            RATE_LIMITED -> "Trop de requêtes — pause"
            NETWORK_ERROR -> "Erreur réseau"
            PARSE_ERROR -> "Réponse illisible"
        }

    /** Full sentence shown when the user opens the watch or the radar page. */
    val explanation: String
        get() = when (this) {
            NEVER -> "Cette veille n'a pas encore été scannée."
            OK -> "Dernier scan réussi."
            EMPTY -> "Vinted n'a retourné aucune annonce pour ces critères."
            NEEDS_LOGIN -> "Ouvrez Réglages ▸ Connexion Vinted et connectez-vous à votre compte, " +
                "puis relancez la surveillance."
            NEEDS_VERIFICATION -> "Une vérification Vinted est nécessaire.\n\n" +
                "Ouvrez Vinted, terminez la vérification puis relancez la surveillance."
            RATE_LIMITED -> "Vinted a limité le nombre de requêtes. RadarDeal ralentit " +
                "automatiquement puis réessaiera. Choisissez une fréquence plus lente si cela se répète."
            NETWORK_ERROR -> "Impossible de joindre Vinted. Vérifiez votre connexion internet."
            PARSE_ERROR -> "La réponse de Vinted n'a pas pu être analysée. RadarDeal réessaiera au prochain scan."
        }
}
