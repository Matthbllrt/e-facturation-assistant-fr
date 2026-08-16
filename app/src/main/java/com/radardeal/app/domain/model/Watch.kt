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
    val intervalSeconds: Int = ScanFrequency.STANDARD.seconds,
    /**
     * Ultra is a single-slot mode: at most one watch may hold it at a time. One search polled
     * every few seconds is worth far more than ten searches saturating the device and getting
     * the session rate limited.
     */
    val isUltra: Boolean = false,
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

    /** The frequency preset this watch's interval corresponds to. */
    val frequency: ScanFrequency
        get() = if (isUltra) ScanFrequency.ULTRA else ScanFrequency.fromSeconds(intervalSeconds)

    /** Effective polling floor in milliseconds, before adaptive back-off is applied. */
    val baseIntervalMs: Long
        get() = if (isUltra) ScanFrequency.ULTRA.millis else intervalSeconds * 1000L

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

/**
 * Preset polling intervals offered in the UI.
 *
 * These are *floors on how often RadarDeal asks*, not promises about how fast Vinted publishes.
 * A listing that only becomes visible server-side after ten seconds cannot be detected sooner,
 * whatever this value says — see PERFORMANCE.md for what is actually measurable.
 */
enum class ScanFrequency(
    val seconds: Int,
    val label: String,
    val description: String,
    /** Shown under the label so the battery cost is never a surprise. */
    val costHint: String,
    val badge: String,
) {
    ULTRA(3, "Ultra", "2–3 sec", "Vitesse maximale · consommation élevée", "⚡"),
    FAST(5, "Rapide", "5 sec", "Très réactif", "🔥"),
    STANDARD(15, "Standard", "15 sec", "Bon équilibre", "●"),
    ECO(30, "Éco", "30 sec", "Économe en batterie", "🌙");

    val millis: Long get() = seconds * 1000L

    companion object {
        /** Presets a watch can be assigned directly; Ultra is granted through its own path. */
        val selectable: List<ScanFrequency> get() = entries

        fun fromSeconds(seconds: Int): ScanFrequency =
            entries.filter { it != ULTRA }
                .minByOrNull { kotlin.math.abs(it.seconds - seconds) } ?: STANDARD
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
    PARSE_ERROR,

    /**
     * Monitoring stopped itself because Vinted asked for a human check. RadarDeal makes no
     * further request for this watch until the user has cleared it — hammering a site that is
     * explicitly refusing is both rude and counter-productive.
     */
    PAUSED_VERIFICATION;

    val isProblem: Boolean
        get() = this == NEEDS_LOGIN || this == NEEDS_VERIFICATION || this == PAUSED_VERIFICATION ||
            this == RATE_LIMITED || this == NETWORK_ERROR || this == PARSE_ERROR

    /** True when RadarDeal must stop requesting until the user acts. */
    val haltsScanning: Boolean
        get() = this == PAUSED_VERIFICATION

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
            PAUSED_VERIFICATION -> "Surveillance en pause — vérification Vinted"
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
            PAUSED_VERIFICATION -> "Une vérification Vinted est nécessaire.\n\n" +
                "RadarDeal a arrêté d'interroger Vinted pour cette veille. Ouvrez Vinted, " +
                "terminez la vérification, puis relancez la surveillance."
        }
}
