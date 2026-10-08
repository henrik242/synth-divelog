package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.math.max

/**
 * Compressibility factor Z of breathing-gas mixes at 20 C, from quadratic fits
 * Z(P) = 1 + a1*P + a2*P^2 (P in bar, valid 0-300 bar) per pure gas, mixed by
 * mole fraction (Kay's rule).
 *
 * O2 is the one that matters: Z is about 0.92 at 200 bar, and ignoring it
 * leaves a blend about 0.8 points rich in O2, outside the 0.5 point tolerance.
 * He is close to ideal.
 */
internal object Compressibility {
    private class Fit(val a1: Double, val a2: Double) {
        fun z(bar: Double): Double = 1 + a1 * bar + a2 * bar * bar
    }

    private val OXYGEN = Fit(-1.2e-4, -1.3e-6)
    private val NITROGEN = Fit(-9.0e-5, -1.0e-7)
    private val HELIUM = Fit(3.1e-5, 0.0)

    /** Z for a mix with O2 and He mole fractions [o2] and [he] at [bar]. */
    fun z(o2: Double, he: Double, bar: Double): Double {
        if (bar <= 0) return 1.0
        val n2 = max(0.0, 1 - o2 - he)
        return o2 * OXYGEN.z(bar) + he * HELIUM.z(bar) + n2 * NITROGEN.z(bar)
    }

    /**
     * Gauge pressure of a mix whose amount is [moleBar] (ideal-gas bar, so
     * proportional to moles): solves P = Z(P) * moleBar by fixed-point iteration.
     */
    fun gaugeBar(moleBar: Double, o2: Double, he: Double): Double {
        if (moleBar <= 0) return 0.0
        var p = moleBar
        repeat(5) {
            val next = z(o2, he, p) * moleBar
            if (abs(next - p) < 0.001) return next
            p = next
        }
        return p
    }
}
