package no.synth.divelog.core.gas

import kotlin.math.round

/** GUE's standard gases plus air, from the leanest bottom gas to oxygen. */
val STANDARD_GASES: List<BreathingGas> = listOf(
    BreathingGas(100, 700), BreathingGas(150, 550), BreathingGas(180, 450), BreathingGas(210, 350),
    BreathingGas.AIR, BreathingGas(320), BreathingGas(500), BreathingGas.OXYGEN,
)

/** Deco gases a suggestion picks from, deepest switch first. */
private val DECO_CANDIDATES = listOf(BreathingGas(210, 350), BreathingGas(500), BreathingGas.OXYGEN)

/** A bottom gas and deco gases, deepest switch first, for a profile. */
data class GasSuggestion(val bottom: BreathingGas, val deco: List<BreathingGas>)

/**
 * Gases for a dive to [maxDepthMm]. The bottom gas is the standard gas richest in O2 (then the
 * least helium) within [bottomPpO2Mbar] and [maxEndMm] at the deepest point, or a best mix when
 * no standard gas fits. The deco gases are oxygen and EAN50, plus 21/35 under a hypoxic bottom
 * gas, each richer than the bottom gas and switched to shallower than [maxDepthMm] at
 * [decoPpO2Mbar]. Null when no mix fits the limits.
 */
fun suggestGases(
    maxDepthMm: Int,
    bottomPpO2Mbar: Int = 1400,
    decoPpO2Mbar: Int = 1600,
    maxEndMm: Int? = 30000,
    water: WaterColumn = WaterColumn(),
    stepMm: Int = 3000,
): GasSuggestion? {
    require(maxDepthMm > 0) { "Depth must be above 0" }
    val mbar = water.mbar(maxDepthMm)
    fun fits(gas: BreathingGas): Boolean {
        val ppO2 = round(gas.o2 * mbar / 1000.0)
        val end = water.depthMm(mbar * (1000 - gas.he) / 1000)
        return ppO2 <= bottomPpO2Mbar && ppO2 >= MIN_PPO2 * 1000 && (maxEndMm == null || end <= maxEndMm)
    }
    val bottom = STANDARD_GASES.filter(::fits)
        .maxWithOrNull(compareBy<BreathingGas> { it.o2 }.thenByDescending { it.he })
        ?: bestMix(mbar, bottomPpO2Mbar, maxEndMm, water)?.takeIf(::fits)
        ?: return null
    val deco = DECO_CANDIDATES.filter { gas ->
        gas.o2 > bottom.o2 &&
            (gas.he == 0 || bottom.o2 < 210) &&
            switchDepthMm(gas, decoPpO2Mbar, maxEndMm, water, stepMm) in 1 until maxDepthMm
    }
    return GasSuggestion(bottom, deco)
}

/**
 * The mix with the most O2 within [ppO2Mbar] at [ambientMbar] and the least helium that keeps
 * the END (O2 narcotic) within [maxEndMm], in whole percent. Null when they do not fit together.
 */
private fun bestMix(ambientMbar: Int, ppO2Mbar: Int, maxEndMm: Int?, water: WaterColumn): BreathingGas? {
    val o2 = ppO2Mbar * 100 / ambientMbar * 10
    val narcoticMax = maxEndMm?.let { water.mbar(it) * 1000 / ambientMbar } ?: 1000
    val he = ((1000 - narcoticMax).coerceAtLeast(0) + 9) / 10 * 10
    if (o2 <= 0 || o2 + he > 1000) return null
    return BreathingGas(o2, he)
}

/** Where the planner switches to [gas]: at [ppO2Mbar], or shallower to keep within [maxEndMm]. */
private fun switchDepthMm(gas: BreathingGas, ppO2Mbar: Int, maxEndMm: Int?, water: WaterColumn, stepMm: Int): Int {
    val byPpO2 = water.switchDepthMm(gas, ppO2Mbar, stepMm)
    if (maxEndMm == null || gas.he >= 1000) return byPpO2
    val byEnd = water.depthMm(water.mbar(maxEndMm) * 1000 / (1000 - gas.he))
    return minOf(byPpO2, byEnd / stepMm * stepMm)
}
