package no.synth.divelog.core.gas

/** GUE's standard gases plus air, from the leanest bottom gas to oxygen; what [suggestGases] picks from. */
val STANDARD_GASES: List<BreathingGas> = listOf(
    BreathingGas(100, 700), BreathingGas(150, 550), BreathingGas(180, 450), BreathingGas(210, 350),
    BreathingGas.AIR, BreathingGas(320), BreathingGas(500), BreathingGas.OXYGEN,
)

/** The richest bottom gas a suggestion picks: EAN32. Richer mixes are deco gases. */
private const val MAX_BOTTOM_O2 = 320

private val TRIMIX_21_35 = BreathingGas(210, 350)

/** Deco gases a suggestion picks from, deepest switch first. */
private val DECO_CANDIDATES = listOf(TRIMIX_21_35, BreathingGas(500), BreathingGas.OXYGEN)

/** A bottom gas and deco gases, deepest switch first, for a profile. */
data class GasSuggestion(val bottom: BreathingGas, val deco: List<BreathingGas>)

/**
 * Gases for a dive to [maxDepthMm]. The bottom gas is the standard gas richest in O2 (then the
 * least helium), up to EAN32, within [bottomPpO2Mbar] and [maxEndMm] at the deepest point, or a
 * best mix when no standard gas fits. The deco gases are oxygen and EAN50, plus 21/35 as travel
 * and deco gas when the bottom gas is hypoxic at the surface, each richer than the bottom gas
 * and switched to shallower than [maxDepthMm] at [decoPpO2Mbar]. Null when no mix fits the
 * limits.
 */
fun suggestGases(
    maxDepthMm: Int,
    bottomPpO2Mbar: Int = 1400,
    decoPpO2Mbar: Int = 1600,
    maxEndMm: Int? = 30000,
    water: WaterColumn = WaterColumn(),
    imperialStops: Boolean = false,
): GasSuggestion? {
    require(maxDepthMm > 0) { "Depth must be above 0" }
    val mbar = water.mbar(maxDepthMm)
    // Unrounded, as the planner's pO2 warning compares: o2 permille x mbar against mbar x 1000.
    fun fits(gas: BreathingGas): Boolean {
        val ppO2 = gas.o2.toLong() * mbar
        val end = water.depthMm(mbar * (1000 - gas.he) / 1000)
        return ppO2 <= bottomPpO2Mbar * 1000L && ppO2 >= MIN_PPO2 * 1_000_000 && (maxEndMm == null || end <= maxEndMm)
    }
    val bottom = STANDARD_GASES.filter { it.o2 <= MAX_BOTTOM_O2 && fits(it) }
        .maxWithOrNull(compareBy<BreathingGas> { it.o2 }.thenByDescending { it.he })
        ?: bestMix(mbar, bottomPpO2Mbar, maxEndMm, water)?.takeIf(::fits)
        ?: return null
    val stepMm = stopStepMm(imperialStops)
    val deco = DECO_CANDIDATES.filter { gas ->
        gas.o2 > bottom.o2 &&
            (gas != TRIMIX_21_35 || water.hypoxicAtSurface(bottom)) &&
            water.decoSwitchDepthMm(gas, decoPpO2Mbar, maxEndMm, stepMm) in 1 until maxDepthMm
    }
    return GasSuggestion(bottom, deco)
}

/**
 * The mix with the most O2 (up to EAN32) within [ppO2Mbar] at [ambientMbar] and the least
 * helium that keeps the END (O2 narcotic) within [maxEndMm], in whole percent. Null when they
 * do not fit together.
 */
private fun bestMix(ambientMbar: Int, ppO2Mbar: Int, maxEndMm: Int?, water: WaterColumn): BreathingGas? {
    val o2 = (ppO2Mbar * 100 / ambientMbar * 10).coerceAtMost(MAX_BOTTOM_O2)
    val narcoticMax = maxEndMm?.let { water.mbar(it) * 1000 / ambientMbar } ?: 1000
    val he = ((1000 - narcoticMax).coerceAtLeast(0) + 9) / 10 * 10
    if (o2 <= 0 || o2 + he > 1000) return null
    return BreathingGas(o2, he)
}
