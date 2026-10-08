package no.synth.divelog.core.gas

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port must plan exactly like the scuba-tools web blender. `web-blender-golden.txt`
 * holds its output, one line per case, for the grid below in the same nested order
 * (start, target, gas set), 12 L cylinder. Pressures, percentages and litres are x100.
 */
class WebBlenderParityTest {
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
    fun matchesWebBlender() {
        val expected = requireNotNull(javaClass.getResourceAsStream("/web-blender-golden.txt")) { "missing golden" }
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val actual = buildList {
            for ((sp, so2, she) in starts) for ((to2, the, tp) in targets) for (set in sets) {
                val plan = planBlend(
                    Cylinder(12.0, sp.toDouble(), so2.toDouble(), she.toDouble()),
                    Fill(to2.toDouble(), the.toDouble(), tp.toDouble()),
                    set.map { requireNotNull(gas[it]) },
                )
                add(render(plan))
            }
        }
        assertEquals(expected.size, actual.size)
        val mismatches = expected.indices.filter { expected[it] != actual[it] }
            .map { "case $it\n  web:    ${expected[it]}\n  kotlin: ${actual[it]}" }
        assertEquals(emptyList(), mismatches, mismatches.joinToString("\n"))
    }

    private fun c(x: Double) = floor(x * 100 + 0.5).toLong()

    private fun m(mix: Mix) = "${pretty(mix.o2)}/${pretty(mix.he)}"

    private fun pretty(percent: Double) = floor(percent * 10 + 0.5).toLong().let { if (it % 10 == 0L) "${it / 10}" else "${it / 10}.${it % 10}" }

    private fun render(plan: BlendPlan): String {
        val r = plan.result
        val steps = plan.steps.joinToString(";") { s ->
            val kind = when (s) {
                is BlendStep.Drain -> "D"
                is BlendStep.Add -> "A:${s.gas.name}"
            }
            "$kind,${c(s.fromBar)},${c(s.toBar)},${m(s.mixBefore)},${m(s.mixAfter)}"
        }
        val usage = plan.litresUsed.entries.joinToString(";") { (k, v) -> "$k=${c(v)}" }
        return "${if (plan.ok) "ok" else "fail"}|${c(r.o2)},${c(r.he)},${c(r.pressureBar)}|$steps|$usage"
    }
}
