// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.model.units

/**
 * Conversions from the fixed storage units to human units.
 *
 * Storage units: depth millimetres, pressure millibar, temperature millikelvin,
 * duration seconds, gas fractions permille, volume millilitres. Everything is an
 * integer in storage; these helpers produce display values only.
 */
object Units {
    private const val MM_PER_METRE = 1_000.0
    private const val MM_PER_FOOT = 304.8
    private const val MBAR_PER_BAR = 1_000.0
    private const val PSI_PER_MBAR = 0.014503773773
    private const val KELVIN_ZERO_CELSIUS_MK = 273_150.0
    private const val MK_PER_KELVIN = 1_000.0
    private const val ML_PER_LITRE = 1_000.0
    private const val CUFT_PER_ML = 0.0000353146667

    // Depth
    fun depthMetres(mm: Int): Double = mm / MM_PER_METRE
    fun depthFeet(mm: Int): Double = mm / MM_PER_FOOT

    // Pressure
    fun pressureBar(mbar: Int): Double = mbar / MBAR_PER_BAR
    fun pressurePsi(mbar: Int): Double = mbar * PSI_PER_MBAR

    // Temperature
    fun temperatureCelsius(mk: Int): Double = (mk - KELVIN_ZERO_CELSIUS_MK) / MK_PER_KELVIN
    fun temperatureFahrenheit(mk: Int): Double = temperatureCelsius(mk) * 9.0 / 5.0 + 32.0

    // Gas fraction
    fun gasPercent(permille: Int): Double = permille / 10.0
    fun gasFraction(permille: Int): Double = permille / 1_000.0

    // Volume
    fun volumeLitres(ml: Int): Double = ml / ML_PER_LITRE
    fun volumeCubicFeet(ml: Int): Double = ml * CUFT_PER_ML

    // Duration helpers (always stored in seconds)
    fun durationMinutesPart(seconds: Int): Int = seconds / 60
    fun durationSecondsPart(seconds: Int): Int = seconds % 60

    // System-aware selectors returning (value, unit label).
    fun depth(mm: Int, system: UnitSystem): Measure =
        when (system) {
            UnitSystem.METRIC -> Measure(depthMetres(mm), "m")
            UnitSystem.IMPERIAL -> Measure(depthFeet(mm), "ft")
        }

    fun pressure(mbar: Int, system: UnitSystem): Measure =
        when (system) {
            UnitSystem.METRIC -> Measure(pressureBar(mbar), "bar")
            UnitSystem.IMPERIAL -> Measure(pressurePsi(mbar), "psi")
        }

    fun temperature(mk: Int, system: UnitSystem): Measure =
        when (system) {
            UnitSystem.METRIC -> Measure(temperatureCelsius(mk), "°C")
            UnitSystem.IMPERIAL -> Measure(temperatureFahrenheit(mk), "°F")
        }

    fun volume(ml: Int, system: UnitSystem): Measure =
        when (system) {
            UnitSystem.METRIC -> Measure(volumeLitres(ml), "L")
            UnitSystem.IMPERIAL -> Measure(volumeCubicFeet(ml), "cuft")
        }
}

/** A converted value together with the unit label for the active [UnitSystem]. */
data class Measure(val value: Double, val unit: String)
