package no.synth.divelog.core.gas

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Depth per bar of water, as dive tables reckon it: 10 m (33 ft) of sea water, 10.3 m (34 ft)
 * of fresh water. Surface pressure is taken as 1 bar.
 */
enum class WaterScale(val depthPerBar: Double) {
    METRES_SALT(10.0),
    METRES_FRESH(10.3),
    FEET_SALT(33.0),
    FEET_FRESH(34.0),
}

/** Lowest pO2 that keeps a diver conscious, bar. */
const val MIN_PPO2 = 0.16

/** Share of air that is nitrogen, for the N2-only narcosis convention. */
private const val AIR_N2 = 0.79

/** Depth limits for a mix; depths are in the [WaterScale]'s unit, gas percentages 0-100. */
object DepthLimits {
    fun ambientBar(depth: Double, scale: WaterScale): Double = 1 + depth / scale.depthPerBar

    private fun depthAt(bar: Double, scale: WaterScale): Double = (bar - 1) * scale.depthPerBar

    /** Maximum operating depth: where the pO2 reaches [maxPpO2]. Rounded down to 0.1. */
    fun mod(o2: Double, maxPpO2: Double, scale: WaterScale): Double {
        require(o2 > 0) { "O2 must be above 0 %" }
        return down(depthAt(maxPpO2 / (o2 / 100), scale))
    }

    /**
     * Shallowest depth where a hypoxic mix keeps the pO2 at [MIN_PPO2]; 0 when it is
     * breathable at the surface. Rounded up to 0.1.
     */
    fun minimumDepth(o2: Double, scale: WaterScale): Double {
        require(o2 > 0) { "O2 must be above 0 %" }
        return max(0.0, up(depthAt(MIN_PPO2 / (o2 / 100), scale)))
    }

    /**
     * Equivalent narcotic depth: the air depth with the same narcotic load as [o2]/[he] at
     * [depth]. With [o2Narcotic] both O2 and N2 count (He is the only non-narcotic part);
     * without it only N2 counts, which for nitrox is the equivalent air depth. Rounded up
     * to 0.1, never below the surface.
     */
    fun end(o2: Double, he: Double, depth: Double, scale: WaterScale, o2Narcotic: Boolean): Double =
        max(0.0, up(depthAt(ambientBar(depth, scale) * narcoticShare(o2, he, o2Narcotic), scale)))

    /**
     * Deepest depth where [o2]/[he] stays within an END of [maxEnd]: the narcosis limit, as [mod]
     * is the oxygen limit. Rounded down to 0.1. Null when the mix holds nothing narcotic.
     */
    fun depthForEnd(o2: Double, he: Double, maxEnd: Double, scale: WaterScale, o2Narcotic: Boolean): Double? {
        val narcotic = narcoticShare(o2, he, o2Narcotic)
        if (narcotic <= 1e-9) return null
        return down(depthAt(ambientBar(maxEnd, scale) / narcotic, scale))
    }

    /** The mix's narcotic load relative to air. */
    private fun narcoticShare(o2: Double, he: Double, o2Narcotic: Boolean): Double =
        if (o2Narcotic) 1 - he / 100 else (1 - o2 / 100 - he / 100) / AIR_N2

    fun ppO2(o2: Double, depth: Double, scale: WaterScale): Double = ambientBar(depth, scale) * o2 / 100

    // To 0.1, with slack so float noise does not turn an exact 40 into 39.9.
    private fun down(v: Double) = floor(v * 10 + 1e-6) / 10

    private fun up(v: Double) = ceil(v * 10 - 1e-6) / 10
}
