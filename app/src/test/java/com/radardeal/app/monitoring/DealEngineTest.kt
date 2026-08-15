package com.radardeal.app.monitoring

import com.google.common.truth.Truth.assertThat
import com.radardeal.app.domain.model.DealRating
import org.junit.Test

/**
 * The deal detector. Its most important property is restraint: it must stay silent on thin
 * samples, because a wrong "EXCELLENT DEAL" badge costs the user's trust in the whole app.
 */
class DealEngineTest {

    @Test
    fun `median of an odd sample is the middle value`() {
        assertThat(DealEngine.median(listOf(10.0, 30.0, 20.0))).isEqualTo(20.0)
    }

    @Test
    fun `median of an even sample averages the two middle values`() {
        assertThat(DealEngine.median(listOf(10.0, 20.0, 30.0, 40.0))).isEqualTo(25.0)
    }

    @Test
    fun `median ignores non-positive and non-finite values`() {
        val values = listOf(10.0, -5.0, 0.0, Double.NaN, 30.0, 20.0)
        assertThat(DealEngine.median(values)).isEqualTo(20.0)
    }

    @Test
    fun `median of nothing is null`() {
        assertThat(DealEngine.median(emptyList())).isNull()
        assertThat(DealEngine.median(listOf(-1.0, 0.0))).isNull()
    }

    @Test
    fun `robust median discards bundle-priced outliers`() {
        // One 1 € "lot de photos" among real 60-80 € listings.
        val prices = listOf(1.0, 60.0, 65.0, 70.0, 75.0, 80.0)
        val robust = DealEngine.robustMedian(prices)!!
        assertThat(robust).isAtLeast(65.0)
    }

    // --- Assessment ----------------------------------------------------------------------------

    private val tenPricesAroundSixtySeven = listOf(
        50.0, 55.0, 60.0, 65.0, 67.0, 68.0, 70.0, 75.0, 80.0, 90.0,
    )

    @Test
    fun `stays silent below the minimum number of comparables`() {
        val fewPrices = listOf(60.0, 70.0, 80.0)
        val assessment = DealEngine.assess(price = 20.0, allPrices = fewPrices)

        assertThat(assessment.rating).isEqualTo(DealRating.NONE)
        assertThat(assessment.discount).isNull()
        assertThat(assessment.comparableCount).isEqualTo(3)
    }

    @Test
    fun `flags an excellent deal well under the median`() {
        val assessment = DealEngine.assess(price = 38.0, allPrices = tenPricesAroundSixtySeven)

        assertThat(assessment.rating).isEqualTo(DealRating.EXCELLENT)
        assertThat(assessment.median).isEqualTo(67.5)
        assertThat(assessment.discount!!).isWithin(0.01).of(0.437)
    }

    @Test
    fun `flags a good price moderately under the median`() {
        val assessment = DealEngine.assess(price = 52.0, allPrices = tenPricesAroundSixtySeven)
        assertThat(assessment.rating).isEqualTo(DealRating.GOOD)
    }

    @Test
    fun `does not flag a price close to the median`() {
        val assessment = DealEngine.assess(price = 66.0, allPrices = tenPricesAroundSixtySeven)
        assertThat(assessment.rating).isEqualTo(DealRating.NONE)
    }

    @Test
    fun `does not flag a price above the median`() {
        val assessment = DealEngine.assess(price = 95.0, allPrices = tenPricesAroundSixtySeven)
        assertThat(assessment.rating).isEqualTo(DealRating.NONE)
        assertThat(assessment.discount).isNull()
    }

    @Test
    fun `handles a listing with no price`() {
        val assessment = DealEngine.assess(price = null, allPrices = tenPricesAroundSixtySeven)
        assertThat(assessment.rating).isEqualTo(DealRating.NONE)
    }

    @Test
    fun `thresholds are exactly at the documented boundaries`() {
        val prices = List(10) { 100.0 }

        assertThat(DealEngine.assess(65.0, prices).rating).isEqualTo(DealRating.EXCELLENT)
        assertThat(DealEngine.assess(66.0, prices).rating).isEqualTo(DealRating.GOOD)
        assertThat(DealEngine.assess(80.0, prices).rating).isEqualTo(DealRating.GOOD)
        assertThat(DealEngine.assess(81.0, prices).rating).isEqualTo(DealRating.NONE)
    }
}
