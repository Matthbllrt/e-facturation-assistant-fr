package com.glasscontrol.dyson.domain

import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.domain.model.DysonState

/**
 * Decides which controls and readings a machine really offers.
 *
 * The family gives a starting point, but two machines of the same family can
 * differ — a carbon filter may not be fitted, an older firmware may not report
 * NO2. Once we have seen a state we narrow the flags down to what was actually
 * present, so the UI never shows a control the machine will ignore.
 */
object CapabilityResolver {

    fun resolve(family: DysonFamily, state: DysonState?): DysonCapabilities {
        val base = DysonCapabilities.forFamily(family)
        if (state == null || state.lastUpdatedEpochMs == 0L) return base
        return refine(base, state)
    }

    /**
     * Narrows [base] using observed values.
     *
     * Only ever removes capabilities: a sensor that has not reported yet (because
     * the machine is off, or still warming up) must not permanently disable the
     * reading, so absence is treated as "unknown" for the sensors the family
     * guarantees, and as "not fitted" for the optional ones.
     */
    private fun refine(base: DysonCapabilities, state: DysonState): DysonCapabilities {
        return base.copy(
            // Optional hardware: reported as "INV" or omitted when not fitted.
            carbonFilter = base.carbonFilter && state.carbonFilterPercent != null,
            hepaFilter = base.hepaFilter && state.hepaFilterPercent != null,
            filterHours = base.filterHours && state.filterHours != null,
            // Firmware-dependent sensors.
            no2 = base.no2 && state.no2 != null,
            pm10 = base.pm10 && state.pm10 != null,
            // Heating hardware announces itself through a target temperature.
            targetTemperature = base.targetTemperature && state.targetTemperatureC != null,
        )
    }
}
