package no.synth.divelog.core.gas

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Against the scuba-tools web blender this was ported from. `web-blender-golden.txt` holds its
 * output, one line per case, for the grid below in the same nested order (start, target, gas
 * set), 12 L cylinder; pressures, percentages and litres are x100. This blender keeps more gas
 * (drains only what overshoots) and adds gas by the mix's compressibility, so plans differ, but
 * every case the web one solved must still be solved, without draining further.
 */
class WebBlenderComparisonTest {
    private val gas = mapOf(
        "air" to SourceGas("Air", 21.0, 0.0),
        "o2" to SourceGas("O2", 100.0, 0.0),
        "he" to SourceGas("Helium", 0.0, 100.0),
        "ean32" to SourceGas("Nitrox 32", 32.0, 0.0),
        "tx1070" to SourceGas("10/70", 10.0, 70.0),
    )

    // pressure, O2, He
    private val starts = listOf(
        listOf(0, 0, 0), listOf(50, 21, 0), listOf(100, 32, 0), listOf(100, 18, 45),
        listOf(150, 21, 35), listOf(200, 10, 70), listOf(30, 50, 0),
    )

    // O2, He, pressure
    private val targets = listOf(
        listOf(21, 0, 200), listOf(32, 0, 200), listOf(36, 0, 232), listOf(18, 45, 220),
        listOf(21, 35, 200), listOf(10, 70, 220), listOf(15, 55, 230), listOf(50, 0, 200),
        listOf(100, 0, 200),
    )

    private val sets = listOf(
        listOf("air", "o2", "he"), listOf("air", "o2", "he", "ean32"), listOf("air", "he"),
        listOf("air", "o2", "tx1070"), listOf("air", "tx1070"), listOf("ean32", "o2", "he"),
        listOf("o2"),
    )

    @Test
    fun solvesEverythingTheWebBlenderSolvedWithoutDrainingMore() {
        val expected = requireNotNull(javaClass.getResourceAsStream("/web-blender-golden.txt")) { "missing golden" }
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val problems = mutableListOf<String>()
        var index = 0
        for ((sp, so2, she) in starts) for ((to2, the, tp) in targets) for (set in sets) {
            val web = expected[index++]
            val plan = planBlend(
                Cylinder(12.0, sp.toDouble(), so2.toDouble(), she.toDouble()),
                Fill(to2.toDouble(), the.toDouble(), tp.toDouble()),
                set.map { requireNotNull(gas[it]) },
            )
            val case = "$sp bar $so2/$she -> $to2/$the at $tp bar from $set"
            if (!web.startsWith("ok")) continue
            if (!plan.ok) problems += "$case: web solved it, now ${plan.result}"
            // "D,<from x100>,<to x100>,..." is the web plan's drain step, if any.
            val webKept = web.split("|")[2].split(";").firstOrNull { it.startsWith("D,") }
                ?.split(",")?.get(2)?.toDouble()?.div(100) ?: sp.toDouble()
            val kept = (plan.steps.firstOrNull() as? BlendStep.Drain)?.toBar ?: sp.toDouble()
            if (kept < webKept - 0.05) problems += "$case: drains to $kept bar, web kept $webKept"
        }
        assertEquals(expected.size, index)
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }
}
