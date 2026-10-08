package no.synth.divelog.core.gas

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port must match the scuba-tools tank calculator exactly. `web-tank-golden.txt` holds its
 * output for each tank below under all 16 option combinations (bit 0 aluminium, 1 salt water,
 * 2 valve, 3 doubles): metric lines are empty/full kg, empty/full lbs, total kg and litres, then
 * the calculation with HTML stripped; imperial lines omit the totals.
 */
class WebTankParityTest {
    private val metric = listOf(
        MetricTank(10.0, 200.0, 12.6), MetricTank(12.0, 200.0, 14.0), MetricTank(15.0, 200.0, 16.5),
        MetricTank(18.0, 200.0, 19.8), MetricTank(12.0, 232.0, 14.5), MetricTank(7.0, 300.0, 10.5),
        MetricTank(10.0, 300.0, 14.0), MetricTank(11.1, 207.0, 14.2), MetricTank(10.0, 207.0, 13.4),
        MetricTank(3.0, 200.0, 3.8), MetricTank(5.0, 200.0, 5.5), MetricTank(7.0, 200.0, 7.5),
    )
    private val imperial = listOf(
        ImperialTank(80.0, 3500.0, 28.5), ImperialTank(85.0, 3442.0, 33.0), ImperialTank(100.0, 3442.0, 38.0),
        ImperialTank(117.0, 3442.0, 44.0), ImperialTank(120.0, 3442.0, 43.0), ImperialTank(63.0, 3000.0, 26.0),
        ImperialTank(80.0, 3000.0, 31.4), ImperialTank(100.0, 3300.0, 39.0), ImperialTank(19.0, 3000.0, 11.0),
        ImperialTank(30.0, 3000.0, 14.5), ImperialTank(40.0, 3000.0, 17.0), ImperialTank(77.4, 3000.0, 31.4),
    )

    @Test
    fun matchesWebCalculator() {
        val expected = requireNotNull(javaClass.getResourceAsStream("/web-tank-golden.txt")) { "missing golden" }
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val actual = buildList {
            for (o in 0 until 16) {
                val setup = TankSetup(
                    metal = if (o and 1 != 0) TankMetal.ALUMINIUM else TankMetal.STEEL,
                    saltWater = o and 2 != 0,
                    valve = o and 4 != 0,
                    doubles = o and 8 != 0,
                )
                metric.forEach { b -> add("M|" + render(buoyancy(b, setup), totals = true)) }
                imperial.forEach { b -> add("I|" + render(buoyancy(b, setup), totals = false)) }
            }
        }
        assertEquals(expected.size, actual.size)
        val mismatches = expected.indices.filter { expected[it] != actual[it] }
            .map { "case $it\n  web:    ${expected[it]}\n  kotlin: ${actual[it]}" }
        assertEquals(emptyList(), mismatches, mismatches.take(5).joinToString("\n"))
    }

    private fun render(b: Buoyancy, totals: Boolean): String {
        val numbers = listOf(b.emptyKg, b.fullKg, b.emptyLbs, b.fullLbs) + if (totals) listOf(b.totalKg, b.totalLitres) else emptyList()
        val steps = b.steps.joinToString(" ; ") { s -> s.result?.let { "${s.text} = $it" } ?: s.text }
        return numbers.joinToString("|") { num(it) } + "|" + steps
    }
}
