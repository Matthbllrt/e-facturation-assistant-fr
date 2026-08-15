package com.radardeal.app.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Small, allocation-cheap formatting helpers shared by the UI and the notification layer.
 *
 * They are deliberately null-tolerant: a Vinted listing can be missing its price, its size or
 * its brand, and no screen may ever crash or show "null" because of it.
 */
object Formatters {

    /** "42 €", "39,50 €", or the [fallback] when the price is unknown. */
    fun price(amount: Double?, currency: String? = "EUR", fallback: String = "Prix inconnu"): String {
        if (amount == null || amount.isNaN() || amount.isInfinite()) return fallback
        val symbol = currencySymbol(currency)
        val rounded = (amount * 100).roundToInt() / 100.0
        val text = if (abs(rounded - rounded.roundToInt()) < 0.005) {
            rounded.roundToInt().toString()
        } else {
            String.format(Locale.FRANCE, "%.2f", rounded)
        }
        return "$text $symbol"
    }

    fun currencySymbol(currency: String?): String = when (currency?.uppercase(Locale.ROOT)) {
        null, "", "EUR" -> "€"
        "GBP" -> "£"
        "USD" -> "$"
        "PLN" -> "zł"
        "CZK" -> "Kč"
        "SEK" -> "kr"
        "HUF" -> "Ft"
        else -> currency
    }

    /** "Il y a 18 sec", "Il y a 4 min", "Il y a 3 h", "Il y a 2 j". */
    fun relativeTime(timestampMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs == null || timestampMs <= 0L) return "Jamais"
        val delta = nowMs - timestampMs
        if (delta < 0) return "À l'instant"
        val seconds = delta / 1000
        return when {
            seconds < 10 -> "À l'instant"
            seconds < 60 -> "Il y a $seconds sec"
            seconds < 3600 -> "Il y a ${seconds / 60} min"
            seconds < 86_400 -> "Il y a ${seconds / 3600} h"
            seconds < 2_592_000 -> "Il y a ${seconds / 86_400} j"
            else -> "Il y a ${seconds / 2_592_000} mois"
        }
    }

    /** Compact countdown used on the watch list: "30 sec", "2 min", "1 h". */
    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds sec"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 60} min"
    }

    /** "-32 %" for a 0.32 ratio. Always returns a signed, rounded integer percentage. */
    fun percent(ratio: Double?): String {
        if (ratio == null || ratio.isNaN() || ratio.isInfinite()) return "—"
        val pct = (ratio * 100).roundToInt()
        return "$pct %"
    }
}
