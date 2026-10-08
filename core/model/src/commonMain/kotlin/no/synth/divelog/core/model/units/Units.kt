package no.synth.divelog.core.model.units

import kotlin.math.round

/**
 * Conversions from the fixed storage units to human units.
 *
 * Storage units: depth millimetres, pressure millibar, temperature millikelvin,
 * duration seconds, gas fractions permille, volume millilitres. Everything is an
 * integer in storage; these helpers produce display values only.
 */
object Units {
    private const val MM_PER_METRE = 1_000.0
    private const val MBAR_PER_BAR = 1_000.0
    private const val MK_PER_KELVIN = 1_000.0
    private const val ML_PER_LITRE = 1_000.0

    // Depth
    fun depthMetres(mm: Int): Double = mm / MM_PER_METRE
    fun depthFeet(mm: Int): Double = mm / MM_PER_FOOT

    // Pressure
    fun pressureBar(mbar: Int): Double = mbar / MBAR_PER_BAR
    fun pressurePsi(mbar: Int): Double = pressureBar(mbar) / BAR_PER_PSI

    // Temperature
    fun temperatureCelsius(mk: Int): Double = (mk - ZERO_CELSIUS_MK) / MK_PER_KELVIN
    fun temperatureFahrenheit(mk: Int): Double = temperatureCelsius(mk) * 9.0 / 5.0 + 32.0

    // Gas fraction
    fun gasPercent(permille: Int): Double = permille / 10.0
    fun gasFraction(permille: Int): Double = permille / 1_000.0

    // Volume
    fun volumeLitres(ml: Int): Double = ml / ML_PER_LITRE
    fun volumeCubicFeet(ml: Int): Double = volumeLitres(ml) / LITRES_PER_CUFT

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

    /**
     * A tank's size as divers name it: its water volume in litres, or in imperial the
     * nominal free gas at its working pressure, ideal gas at 1 atm, as US cylinders are
     * labelled (an AL80 holds 11.1 L at 3000 psi). Litres when the working pressure is unknown.
     */
    fun tankSize(ml: Int, workingPressureMbar: Int?, system: UnitSystem): Measure {
        val bar = workingPressureMbar?.takeIf { it > 0 }?.let(::pressureBar)
        return if (system == UnitSystem.IMPERIAL && bar != null) {
            Measure(volumeCubicFeet(ml) * bar / ATM_BAR, "cuft")
        } else {
            Measure(volumeLitres(ml), "L")
        }
    }

    /** The water volume, ml, of a [size] given in the unit [tankSize] shows. */
    fun tankVolumeMl(size: Double, workingPressureMbar: Int?, system: UnitSystem): Int {
        val perMl = tankSize(1_000_000, workingPressureMbar, system).value / 1_000_000
        return round(size / perMl).toInt()
    }
}

/** A converted value together with the unit label for the active [UnitSystem]. */
data class Measure(val value: Double, val unit: String)
