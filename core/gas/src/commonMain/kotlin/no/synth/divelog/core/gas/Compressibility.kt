// SPDX-License-Identifier: GPL-2.0-only
// The Z model and its coefficients are Subsurface's (core/gas-model.cpp).
package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.ATM_BAR
import kotlin.math.abs
import kotlin.math.max

/**
 * Compressibility factor Z = pV/nRT of breathing-gas mixes at room temperature: Subsurface's
 * cubic virial fits Z = 1 + c1*P + c2*P^2 + c3*P^3 to the data in Perry's Chemical Engineers'
 * Handbook (helium interpolated to 300 K), mixed linearly by mole fraction. P is absolute
 * pressure in bar; the fits cover 0-500 bar, so pressures are clamped to that.
 *
 * At filling pressures N2 and He are stiffer than an ideal gas (Z about 1.06 and 1.09 at
 * 200 bar) and O2 a little softer (0.96). A partial-pressure fill that ignores this comes out
 * richer in O2 than planned: EAN32 and EAN36 to 232 bar by ideal pressures land at about 32.7
 * and 37 %.
 */
internal object Compressibility {
    private class Virial(val c1: Double, val c2: Double, val c3: Double) {
        /** Z - 1. */
        fun excess(bar: Double): Double = bar * (c1 + bar * (c2 + bar * c3))
    }

    private val OXYGEN = Virial(-7.18092073703e-04, 2.81852572808e-06, -1.50290620492e-09)
    private val NITROGEN = Virial(-2.19260353292e-04, 2.92844845532e-06, -2.07613482075e-09)
    private val HELIUM = Virial(4.87320026468e-04, -8.83632921053e-08, 5.33304543646e-11)

    private const val MAX_BAR = 500.0

    /** Z of a mix with O2 and He mole fractions [o2] and [he] at [absBar]. */
    fun z(o2: Double, he: Double, absBar: Double): Double {
        val p = absBar.coerceIn(0.0, MAX_BAR)
        val n2 = max(0.0, 1 - o2 - he)
        return 1 + o2 * OXYGEN.excess(p) + he * HELIUM.excess(p) + n2 * NITROGEN.excess(p)
    }

    fun z(gas: BreathingGas, absBar: Double): Double = z(gas.o2 / 1000.0, gas.he / 1000.0, absBar)

    /**
     * Amount of gas in a cylinder at [gaugeBar], as the absolute pressure it would show as an
     * ideal gas ("ideal bar"): proportional to moles, and to free volume at 1 bar.
     */
    fun idealBar(gaugeBar: Double, o2: Double, he: Double): Double {
        val abs = gaugeBar + ATM_BAR
        return abs / z(o2, he, abs)
    }

    /** Gauge pressure of [idealBar] of a mix: solves P = Z(P) * idealBar by fixed-point iteration. */
    fun gaugeBar(idealBar: Double, o2: Double, he: Double): Double {
        var p = idealBar
        repeat(20) {
            val next = z(o2, he, p) * idealBar
            if (abs(next - p) < 1e-7) return next - ATM_BAR
            p = next
        }
        return p - ATM_BAR
    }
}
