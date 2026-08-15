package com.radardeal.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Formatting is where "null" leaks into a UI. These tests keep that from happening. */
class FormattersTest {

    @Test
    fun `whole prices are shown without decimals`() {
        assertThat(Formatters.price(42.0)).isEqualTo("42 €")
        assertThat(Formatters.price(70.0)).isEqualTo("70 €")
    }

    @Test
    fun `fractional prices keep two decimals`() {
        assertThat(Formatters.price(39.5)).isEqualTo("39,50 €")
    }

    @Test
    fun `an unknown price shows a label, never null`() {
        assertThat(Formatters.price(null)).isEqualTo("Prix inconnu")
        assertThat(Formatters.price(Double.NaN)).isEqualTo("Prix inconnu")
        assertThat(Formatters.price(Double.POSITIVE_INFINITY)).isEqualTo("Prix inconnu")
    }

    @Test
    fun `other marketplaces get their own currency symbol`() {
        assertThat(Formatters.price(20.0, "GBP")).isEqualTo("20 £")
        assertThat(Formatters.price(20.0, "PLN")).isEqualTo("20 zł")
        assertThat(Formatters.price(20.0, null)).isEqualTo("20 €")
    }

    @Test
    fun `relative time reads naturally at every scale`() {
        val now = 1_700_000_000_000L
        assertThat(Formatters.relativeTime(now - 5_000, now)).isEqualTo("À l'instant")
        assertThat(Formatters.relativeTime(now - 18_000, now)).isEqualTo("Il y a 18 sec")
        assertThat(Formatters.relativeTime(now - 240_000, now)).isEqualTo("Il y a 4 min")
        assertThat(Formatters.relativeTime(now - 3 * 3_600_000L, now)).isEqualTo("Il y a 3 h")
        assertThat(Formatters.relativeTime(now - 2 * 86_400_000L, now)).isEqualTo("Il y a 2 j")
    }

    @Test
    fun `a never-scanned watch says so`() {
        assertThat(Formatters.relativeTime(null)).isEqualTo("Jamais")
        assertThat(Formatters.relativeTime(0L)).isEqualTo("Jamais")
    }

    @Test
    fun `a clock that moved backwards does not produce a negative duration`() {
        val now = 1_700_000_000_000L
        assertThat(Formatters.relativeTime(now + 60_000, now)).isEqualTo("À l'instant")
    }

    @Test
    fun `durations use the unit the user picked`() {
        assertThat(Formatters.duration(15)).isEqualTo("15 sec")
        assertThat(Formatters.duration(30)).isEqualTo("30 sec")
        assertThat(Formatters.duration(60)).isEqualTo("1 min")
        assertThat(Formatters.duration(120)).isEqualTo("2 min")
        assertThat(Formatters.duration(3600)).isEqualTo("1 h")
    }

    @Test
    fun `percentages round to whole numbers`() {
        assertThat(Formatters.percent(0.324)).isEqualTo("32 %")
        assertThat(Formatters.percent(null)).isEqualTo("—")
        assertThat(Formatters.percent(Double.NaN)).isEqualTo("—")
    }
}
