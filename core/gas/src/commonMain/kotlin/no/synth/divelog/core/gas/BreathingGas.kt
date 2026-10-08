package no.synth.divelog.core.gas

import kotlin.math.floor

/**
 * A gas mix in permille, the project's storage unit; N2 is the rest. Breathing gases for the
 * planner, and also blending sources such as pure helium, so O2 may be 0.
 */
data class BreathingGas(val o2: Int, val he: Int = 0) {
    init {
        require(o2 in 0..1000 && he in 0..1000 && o2 + he <= 1000) { "Invalid gas $o2/$he" }
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
        val HELIUM = BreathingGas(0, 1000)

        /** From O2 and He fractions, rounded half up to whole permille. */
        fun ofFractions(o2: Double, he: Double): BreathingGas {
            val o2Permille = permille(o2)
            return BreathingGas(o2Permille, permille(he).coerceAtMost(1000 - o2Permille))
        }

        /** From percentages as typed, or null when they are not a mix. */
        fun ofPercent(o2: Double, he: Double): BreathingGas? {
            val o2Permille = permille(o2 / 100)
            val hePermille = permille(he / 100)
            if (o2Permille !in 0..1000 || hePermille !in 0..1000 || o2Permille + hePermille > 1000) return null
            return BreathingGas(o2Permille, hePermille)
        }

        private fun permille(fraction: Double): Int = floor(fraction * 1000 + 0.5).toInt()
    }
}
