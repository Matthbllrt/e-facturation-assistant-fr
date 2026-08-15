package com.radardeal.app.monitoring

import com.radardeal.app.domain.model.DealRating

/**
 * The local "bonne affaire" detector.
 *
 * RadarDeal makes no claim about a real market price. It only compares a listing to the
 * **median of the listings it has itself collected for that same watch**, and every label it
 * produces says exactly that ("32 % sous la médiane des annonces détectées").
 *
 * The engine stays silent until it has seen [MIN_COMPARABLES] priced listings for the watch:
 * a median over three items is noise, and a wrong "EXCELLENT DEAL" badge is worse than none.
 */
object DealEngine {

    /** Below this many priced listings for a watch, no rating is emitted. */
    const val MIN_COMPARABLES = 8

    /** A price this far under the median is an excellent deal. */
    const val EXCELLENT_THRESHOLD = 0.35

    /** A price this far under the median is a good price. */
    const val GOOD_THRESHOLD = 0.20

    /**
     * Outliers distort a median far less than a mean, but a 1 € "lot de photos" listing still
     * drags a small sample. Prices below this fraction of the median are ignored when the
     * median itself is computed on a small sample.
     */
    private const val ABSURD_LOW_RATIO = 0.05

    data class Assessment(
        val rating: DealRating,
        /** 0.32 for "32 % under the median". Null when no median could be computed. */
        val discount: Double?,
        val median: Double?,
        val comparableCount: Int,
    ) {
        companion object {
            val NONE = Assessment(DealRating.NONE, null, null, 0)
        }
    }

    /** Classic median: mean of the two middle values on an even-sized sample. */
    fun median(values: List<Double>): Double? {
        val sorted = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /**
     * Median of [prices] after discarding absurdly low entries, which on Vinted are usually
     * bundle placeholders rather than comparable items.
     */
    fun robustMedian(prices: List<Double>): Double? {
        val raw = median(prices) ?: return null
        val cleaned = prices.filter { it.isFinite() && it > raw * ABSURD_LOW_RATIO }
        return median(cleaned) ?: raw
    }

    /**
     * Rates [price] against the other prices seen for the watch. [allPrices] is expected to
     * include [price] itself; that is harmless for a median.
     */
    fun assess(price: Double?, allPrices: List<Double>): Assessment {
        val usable = allPrices.filter { it.isFinite() && it > 0.0 }
        if (price == null || price <= 0.0 || usable.size < MIN_COMPARABLES) {
            return Assessment.NONE.copy(comparableCount = usable.size)
        }

        val median = robustMedian(usable) ?: return Assessment.NONE.copy(comparableCount = usable.size)
        if (median <= 0.0) return Assessment.NONE.copy(comparableCount = usable.size)

        val discount = (median - price) / median
        val rating = when {
            discount >= EXCELLENT_THRESHOLD -> DealRating.EXCELLENT
            discount >= GOOD_THRESHOLD -> DealRating.GOOD
            else -> DealRating.NONE
        }

        return Assessment(
            rating = rating,
            discount = discount.takeIf { it > 0.0 },
            median = median,
            comparableCount = usable.size,
        )
    }
}
