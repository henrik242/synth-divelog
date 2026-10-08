// SPDX-License-Identifier: GPL-2.0-only
// Depth and pressure convert as in Subsurface (core/dive.cpp, core/units.h).
package no.synth.divelog.core.gas

import kotlin.math.max
import kotlin.math.round

/** Water by salinity in g per 10 l, Subsurface's unit. */
enum class Water(val salinity: Int) {
    SALT(10300),
    FRESH(10000),
    ;

    val kgPerLitre: Double get() = salinity / 10000.0
}

/**
 * The one water model for every calculator: depth to pressure at a surface pressure and
 * salinity. The Int functions round to whole mbar as Subsurface's planner does.
 */
class WaterColumn(val surfaceMbar: Int = 1013, val water: Water = Water.SALT) {
    private val mbarPerMm = water.salinity * 0.981 / 100000.0

    val surfaceBar: Double get() = surfaceMbar / 1000.0

    fun mbar(depthMm: Int): Int = round(surfaceMbar + depthMm * mbarPerMm).toInt()
    fun bar(depthMm: Int): Double = mbar(depthMm) / 1000.0

    /** Depth of an ambient pressure, mm; negative above the surface. */
    fun depthMm(ambientMbar: Int): Int = round((ambientMbar - surfaceMbar) / mbarPerMm).toInt()

    /** Ambient pressure at [metres], bar, unrounded. */
    fun ambientBar(metres: Double): Double = (surfaceMbar + metres * 1000 * mbarPerMm) / 1000

    /** Depth of [ambientBar], metres, unrounded; negative above the surface. */
    fun metres(ambientBar: Double): Double = (ambientBar * 1000 - surfaceMbar) / mbarPerMm / 1000

    /** Smooth ceiling depth for a tolerated ambient pressure, never above the surface. */
    fun ceilingMm(toleratedBar: Double): Int {
        val delta = max(0.0, toleratedBar - surfaceBar)
        return round(round(delta * 1000) / mbarPerMm).toInt()
    }

    /**
     * Depth where [gas] reaches [ppO2Mbar], in whole [stepMm]. Rounds down, except that from
     * 0.9 of a step it rounds up, so oxygen at 1.6 lands on 6 m.
     */
    fun switchDepthMm(gas: BreathingGas, ppO2Mbar: Int, stepMm: Int): Int {
        val depth = depthMm(ppO2Mbar * 1000 / gas.o2).toDouble()
        return (depth / stepMm + 0.1).toInt() * stepMm
    }
}
