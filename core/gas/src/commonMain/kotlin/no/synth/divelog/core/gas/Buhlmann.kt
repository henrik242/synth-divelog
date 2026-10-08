// SPDX-License-Identifier: GPL-2.0-only
// The decompression model follows Subsurface's implementation (core/deco.cpp,
// core/planner.cpp). The ZHL-16C coefficients are Buhlmann's published values.
package no.synth.divelog.core.gas

import kotlin.math.exp
import kotlin.math.max

/** ln(2) / 60, as Subsurface rounds it: converts a half-life in minutes to a per-second rate. */
private const val LN2_PER_MINUTE = 1.155245301e-02

/** Effective water vapour pressure in the lungs, bar (Buhlmann's value, respiratory quotient 1). */
const val WATER_VAPOUR_BAR = 0.0627

/** N2 share of air as tissues start out saturated with it. */
private const val N2_IN_AIR = 0.781

/** The GF-low anchor never sits shallower than this far below the surface, bar. */
private const val GF_LOW_MIN_BAR = 1.0

/** Buhlmann ZHL-16C: per compartment, half-lives in minutes and the a (bar) and b coefficients. */
object Zhl16c {
    const val COMPARTMENTS = 16

    val n2HalfLife = doubleArrayOf(
        5.0, 8.0, 12.5, 18.5, 27.0, 38.3, 54.3, 77.0,
        109.0, 146.0, 187.0, 239.0, 305.0, 390.0, 498.0, 635.0,
    )
    val n2A = doubleArrayOf(
        1.1696, 1.0, 0.8618, 0.7562, 0.62, 0.5043, 0.441, 0.4,
        0.375, 0.35, 0.3295, 0.3065, 0.2835, 0.261, 0.248, 0.2327,
    )
    val n2B = doubleArrayOf(
        0.5578, 0.6514, 0.7222, 0.7825, 0.8126, 0.8434, 0.8693, 0.8910,
        0.9092, 0.9222, 0.9319, 0.9403, 0.9477, 0.9544, 0.9602, 0.9653,
    )
    val heHalfLife = doubleArrayOf(
        1.88, 3.02, 4.72, 6.99, 10.21, 14.48, 20.53, 29.11,
        41.20, 55.19, 70.69, 90.34, 115.29, 147.42, 188.24, 240.03,
    )
    val heA = doubleArrayOf(
        1.6189, 1.383, 1.1919, 1.0458, 0.922, 0.8205, 0.7305, 0.6502,
        0.595, 0.5545, 0.5333, 0.5189, 0.5181, 0.5176, 0.5172, 0.5119,
    )
    val heB = doubleArrayOf(
        0.4770, 0.5747, 0.6527, 0.7223, 0.7582, 0.7957, 0.8279, 0.8553,
        0.8757, 0.8903, 0.8997, 0.9073, 0.9122, 0.9171, 0.9217, 0.9267,
    )
}

/** A breathing gas in permille; N2 is the rest. */
data class BreathingGas(val o2: Int, val he: Int = 0) {
    init {
        require(o2 in 1..1000 && he in 0..1000 && o2 + he <= 1000) { "Invalid gas $o2/$he" }
    }

    val n2: Int get() = 1000 - o2 - he

    /** "Air", "Oxygen", "EAN50" or "18/45". */
    val name: String
        get() = when {
            he == 0 && o2 in 209..211 -> "Air"
            he == 0 && o2 == 1000 -> "Oxygen"
            he == 0 && o2 > 211 -> "EAN${(o2 + 5) / 10}"
            else -> "${(o2 + 5) / 10}/${(he + 5) / 10}"
        }

    companion object {
        val AIR = BreathingGas(210)
        val OXYGEN = BreathingGas(1000)
    }
}

/** Gradient factors as fractions, e.g. 0.3 and 0.75. */
data class GradientFactors(val low: Double, val high: Double) {
    init {
        require(low > 0 && high > 0 && low <= high) { "GF low must be positive and at most GF high" }
    }
}

/**
 * Inert gas loading of the 16 ZHL-16C compartments, in bar, plus the GF-low anchor: the
 * deepest pressure where the GF-low ceiling has been seen, which the GF slope runs from.
 * Mutable for speed; [copy] before a trial.
 */
class Tissues private constructor(
    private val n2: DoubleArray,
    private val he: DoubleArray,
    gfLowAnchorBar: Double,
) {
    var gfLowAnchorBar: Double = gfLowAnchorBar
        private set

    fun n2(compartment: Int): Double = n2[compartment]
    fun he(compartment: Int): Double = he[compartment]

    fun copy(): Tissues = Tissues(n2.copyOf(), he.copyOf(), gfLowAnchorBar)

    /** Breathe [gas] for [seconds] at a constant [ambientBar] (Haldane). */
    fun constantDepth(ambientBar: Double, gas: BreathingGas, seconds: Int) {
        if (seconds <= 0) return
        val inspired = ambientBar - WATER_VAPOUR_BAR
        val pN2 = inspired * gas.n2 / 1000.0
        val pHe = inspired * gas.he / 1000.0
        for (c in 0 until Zhl16c.COMPARTMENTS) {
            n2[c] += (pN2 - n2[c]) * (1 - exp(-seconds * LN2_PER_MINUTE / Zhl16c.n2HalfLife[c]))
            he[c] += (pHe - he[c]) * (1 - exp(-seconds * LN2_PER_MINUTE / Zhl16c.heHalfLife[c]))
        }
    }

    /** Breathe [gas] for [seconds] while ambient pressure changes linearly (Schreiner equation). */
    fun linearChange(startBar: Double, endBar: Double, gas: BreathingGas, seconds: Int) {
        if (seconds <= 0) return
        val rate = (endBar - startBar) / seconds
        val inspired = startBar - WATER_VAPOUR_BAR
        n2.schreiner(inspired * gas.n2 / 1000.0, rate * gas.n2 / 1000.0, seconds, Zhl16c.n2HalfLife)
        he.schreiner(inspired * gas.he / 1000.0, rate * gas.he / 1000.0, seconds, Zhl16c.heHalfLife)
    }

    private fun DoubleArray.schreiner(p0: Double, rate: Double, seconds: Int, halfLives: DoubleArray) {
        for (c in indices) {
            val k = LN2_PER_MINUTE / halfLives[c]
            val decay = exp(-k * seconds)
            this[c] = p0 + rate * (seconds - 1 / k) - (p0 - this[c] - rate / k) * decay
        }
    }

    /**
     * Shallowest ambient pressure the tissues tolerate, bar, with [gf] sloping from GF low at
     * the anchor to GF high at [surfaceBar]. Moves the anchor deeper when the plain GF-low
     * ceiling is deeper than it, as Subsurface does.
     */
    fun toleratedAmbientBar(gf: GradientFactors, surfaceBar: Double): Double {
        val a = DoubleArray(Zhl16c.COMPARTMENTS)
        val b = DoubleArray(Zhl16c.COMPARTMENTS)
        for (c in 0 until Zhl16c.COMPARTMENTS) {
            val total = n2[c] + he[c]
            a[c] = (Zhl16c.n2A[c] * n2[c] + Zhl16c.heA[c] * he[c]) / total
            b[c] = (Zhl16c.n2B[c] * n2[c] + Zhl16c.heB[c] * he[c]) / total
            val gfLowCeiling = (b[c] * total - gf.low * a[c] * b[c]) / ((1 - b[c]) * gf.low + b[c])
            gfLowAnchorBar = max(gfLowAnchorBar, gfLowCeiling)
        }
        val anchor = gfLowAnchorBar
        val lo = gf.low
        val hi = gf.high
        var tolerated = 0.0
        for (c in 0 until Zhl16c.COMPARTMENTS) {
            val total = n2[c] + he[c]
            // Only where the GF line runs from the anchor up to the surface; otherwise no limit.
            val atSurface = (surfaceBar / b[c] + a[c] - surfaceBar) * hi + surfaceBar
            val atAnchor = (anchor / b[c] + a[c] - anchor) * lo + anchor
            if (atSurface < atAnchor) {
                val t = (-a[c] * b[c] * (hi * anchor - lo * surfaceBar) -
                    (1 - b[c]) * (hi - lo) * anchor * surfaceBar +
                    b[c] * (anchor - surfaceBar) * total) /
                    (-a[c] * b[c] * (hi - lo) +
                        (1 - b[c]) * (lo * anchor - hi * surfaceBar) +
                        b[c] * (anchor - surfaceBar))
                tolerated = max(tolerated, t)
            }
        }
        return tolerated
    }

    companion object {
        /** Tissues saturated with air at [surfaceBar], as after a long time at the surface. */
        fun atSurface(surfaceBar: Double): Tissues = Tissues(
            DoubleArray(Zhl16c.COMPARTMENTS) { (surfaceBar - WATER_VAPOUR_BAR) * N2_IN_AIR },
            DoubleArray(Zhl16c.COMPARTMENTS),
            surfaceBar + GF_LOW_MIN_BAR,
        )
    }
}
