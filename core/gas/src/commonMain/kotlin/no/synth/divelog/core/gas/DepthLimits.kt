package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.MM_PER_FOOT
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Lowest pO2 that keeps a diver conscious, bar. */
const val MIN_PPO2 = 0.16

/** Share of air that is nitrogen, for the N2-only narcosis convention. */
private const val AIR_N2 = 0.79

enum class DepthUnit(val metres: Double) {
    METRES(1.0),
    FEET(MM_PER_FOOT / 1000),
}

/**
 * Depth limits of a mix in [water], the same water model as the planner's. Depths are in
 * [unit] and rounded to 0.1 on the safe side: limits down, required depths up.
 */
class DepthLimits(private val water: WaterColumn = WaterColumn(), private val unit: DepthUnit = DepthUnit.METRES) {
    fun ambientBar(depth: Double): Double = water.ambientBar(depth * unit.metres)

    private fun depthAt(bar: Double): Double = water.metres(bar) / unit.metres

    /** Maximum operating depth: where the pO2 reaches [maxPpO2] bar. */
    fun mod(gas: BreathingGas, maxPpO2: Double): Double {
        require(gas.o2 > 0) { "O2 must be above 0 %" }
        return down(depthAt(maxPpO2 / o2(gas)))
    }

    /** Shallowest depth where a hypoxic mix keeps the pO2 at [MIN_PPO2]; 0 when it is breathable at the surface. */
    fun minimumDepth(gas: BreathingGas): Double {
        require(gas.o2 > 0) { "O2 must be above 0 %" }
        return max(0.0, up(depthAt(MIN_PPO2 / o2(gas))))
    }

    /**
     * Equivalent narcotic depth: the air depth with the same narcotic load as [gas] at [depth].
     * With [o2Narcotic] both O2 and N2 count (He is the only non-narcotic part); without it
     * only N2 counts, which for nitrox is the equivalent air depth. Never above the surface.
     */
    fun end(gas: BreathingGas, depth: Double, o2Narcotic: Boolean): Double =
        max(0.0, up(depthAt(ambientBar(depth) * narcoticShare(gas, o2Narcotic))))

    /**
     * Deepest depth where [gas] stays within an END of [maxEnd]: the narcosis limit, as [mod]
     * is the oxygen limit. Null when the mix holds nothing narcotic.
     */
    fun depthForEnd(gas: BreathingGas, maxEnd: Double, o2Narcotic: Boolean): Double? {
        val narcotic = narcoticShare(gas, o2Narcotic)
        if (narcotic <= 1e-9) return null
        return down(depthAt(ambientBar(maxEnd) / narcotic))
    }

    fun ppO2(gas: BreathingGas, depth: Double): Double = ambientBar(depth) * o2(gas)

    private fun o2(gas: BreathingGas): Double = gas.o2 / 1000.0

    /** The mix's narcotic load relative to air. */
    private fun narcoticShare(gas: BreathingGas, o2Narcotic: Boolean): Double =
        if (o2Narcotic) 1 - gas.he / 1000.0 else gas.n2 / 1000.0 / AIR_N2

    // To 0.1, with slack so float noise does not turn an exact 40 into 39.9.
    private fun down(v: Double) = floor(v * 10 + 1e-6) / 10

    private fun up(v: Double) = ceil(v * 10 - 1e-6) / 10
}
