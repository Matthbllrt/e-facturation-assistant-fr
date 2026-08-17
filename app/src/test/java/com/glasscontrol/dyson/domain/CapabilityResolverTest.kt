package com.glasscontrol.dyson.domain

import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolverTest {

    @Test
    fun `maps product types to families including regional variants`() {
        assertEquals(DysonFamily.PURE_COOL, DysonFamily.fromDeviceType("438"))
        assertEquals(DysonFamily.PURE_COOL, DysonFamily.fromDeviceType("438K"))
        assertEquals(DysonFamily.PURE_COOL, DysonFamily.fromDeviceType("438E"))
        assertEquals(DysonFamily.HOT_COOL, DysonFamily.fromDeviceType("527M"))
        assertEquals(DysonFamily.HOT_COOL_LINK, DysonFamily.fromDeviceType("455"))
        assertEquals(DysonFamily.PURE_COOL_LINK, DysonFamily.fromDeviceType("475"))
        assertEquals(DysonFamily.HUMIDIFY_COOL, DysonFamily.fromDeviceType("358E"))
        assertEquals(DysonFamily.BIG_QUIET, DysonFamily.fromDeviceType("664"))
    }

    @Test
    fun `an unknown product type is unsupported rather than a crash`() {
        val family = DysonFamily.fromDeviceType("999")
        assertEquals(DysonFamily.UNSUPPORTED, family)

        val capabilities = CapabilityResolver.resolve(family, null)
        assertFalse(capabilities.fanSpeed)
        assertFalse(capabilities.autoMode)
        assertFalse(capabilities.oscillation)
    }

    @Test
    fun `only heating families expose heat controls`() {
        assertFalse(CapabilityResolver.resolve(DysonFamily.PURE_COOL, null).heating)
        assertTrue(CapabilityResolver.resolve(DysonFamily.HOT_COOL, null).heating)
        assertTrue(CapabilityResolver.resolve(DysonFamily.HOT_COOL_LINK, null).heating)
    }

    @Test
    fun `Link machines report filter hours rather than percentages`() {
        val link = CapabilityResolver.resolve(DysonFamily.PURE_COOL_LINK, null)
        assertTrue(link.filterHours)
        assertFalse(link.hepaFilter)
        assertFalse("Link machines predate the PM sensors", link.pm25)
    }

    @Test
    fun `Big+Quiet machines hide oscillation`() {
        assertFalse(CapabilityResolver.resolve(DysonFamily.BIG_QUIET, null).oscillation)
    }

    @Test
    fun `an observed state removes hardware the machine does not report`() {
        val observed = DysonState(
            hepaFilterPercent = 80,
            carbonFilterPercent = null, // "INV": no carbon filter fitted
            pm10 = 5,
            no2 = null,
            lastUpdatedEpochMs = 1L,
        )

        val capabilities = CapabilityResolver.resolve(DysonFamily.PURE_COOL, observed)

        assertTrue(capabilities.hepaFilter)
        assertFalse("A missing carbon filter must disappear from the UI", capabilities.carbonFilter)
        assertTrue(capabilities.pm10)
        assertFalse(capabilities.no2)
    }

    @Test
    fun `capabilities are not narrowed before any state has arrived`() {
        // lastUpdatedEpochMs == 0 means nothing has been read yet, so the family
        // defaults must survive rather than everything being switched off.
        val capabilities = CapabilityResolver.resolve(DysonFamily.PURE_COOL, DysonState())

        assertTrue(capabilities.carbonFilter)
        assertTrue(capabilities.no2)
    }

    @Test
    fun `heating machines keep the heat control even without a target reading`() {
        val observed = DysonState(targetTemperatureC = null, lastUpdatedEpochMs = 1L)

        val capabilities = CapabilityResolver.resolve(DysonFamily.HOT_COOL, observed)

        assertTrue("The heater exists regardless of the reported target", capabilities.heating)
        assertFalse(capabilities.targetTemperature)
    }
}
