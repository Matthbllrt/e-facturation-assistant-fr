package com.radardeal.app.domain.model

/**
 * One Vinted listing as RadarDeal knows it.
 *
 * Everything except [itemId], [watchId] and the timestamps is nullable on purpose: Vinted's
 * payload shape changes over time and a listing missing its size, brand or even its price must
 * still be storable and displayable.
 */
data class Listing(
    val itemId: String,
    val watchId: Long,
    val title: String? = null,
    val brand: String? = null,
    val size: String? = null,
    val condition: String? = null,
    val price: Double? = null,
    val currency: String? = "EUR",
    val previousPrice: Double? = null,
    val imageUrl: String? = null,
    val itemUrl: String? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val isNew: Boolean = false,
    val isFavorite: Boolean = false,
    val priceDroppedAt: Long? = null,
) {
    /** True when the last scan saw a strictly lower price than the one before. */
    val hasPriceDrop: Boolean
        get() = previousPrice != null && price != null && price < previousPrice

    /** 0.21 for 70 € → 55 €. Null when there is nothing to compare. */
    val priceDropRatio: Double?
        get() {
            val before = previousPrice ?: return null
            val now = price ?: return null
            if (before <= 0.0 || now >= before) return null
            return (before - now) / before
        }

    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Annonce Vinted"

    /** "Nike · 42 · Très bon état" — skips whatever Vinted did not provide. */
    fun subtitle(): String = listOfNotNull(
        brand?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        condition?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** A stored price observation, used to draw the price history of a listing. */
data class PricePoint(
    val price: Double,
    val recordedAt: Long,
)

/** How good a price looks compared to the other listings of the same watch. */
enum class DealRating {
    NONE,
    GOOD,
    EXCELLENT;

    val label: String
        get() = when (this) {
            NONE -> ""
            GOOD -> "BON PRIX"
            EXCELLENT -> "🔥 EXCELLENT DEAL"
        }
}

/**
 * A listing enriched with everything a card needs to render, computed once per query instead of
 * per recomposition.
 *
 * [medianDiscount] is expressed relative to the median of the *listings RadarDeal has actually
 * seen for this watch* — never presented as a market price.
 */
data class ListingCard(
    val listing: Listing,
    val watchName: String,
    val rating: DealRating = DealRating.NONE,
    val medianDiscount: Double? = null,
    val comparableCount: Int = 0,
) {
    /** "32 % sous la médiane des annonces détectées", or null when there is no basis to say it. */
    fun discountLabel(): String? {
        val d = medianDiscount ?: return null
        if (d <= 0.0) return null
        return "${(d * 100).toInt()} % sous la médiane"
    }
}
