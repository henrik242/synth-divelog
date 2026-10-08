package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Free litres at 1 atm per gas. At fill pressures N2 and He are stiffer than an ideal gas
 * (Z > 1), so a fill takes less free gas than bar x volume.
 */
class GasUsageTest {
    private val gases = listOf(
        SourceGas("Air", BreathingGas.AIR),
        SourceGas("O2", BreathingGas.OXYGEN),
        SourceGas("Helium", BreathingGas.HELIUM),
    )

    @Test
    fun tracksUsageFor1845FromEmpty11L() {
        val r = planBlend(Cylinder(11.0, 0.0, BreathingGas.AIR), Fill(pct(18.0, 45.0), 220.0), gases)
        assertTrue(r.ok)
        r.steps.filterIsInstance<BlendStep.Add>().forEach { assertTrue(it.litres > 0) }
        val total = r.litresUsed.values.sum()
        // 221 bar absolute / Z 1.066, less the 1 atm already in the cylinder, over 1 atm, x 11 L.
        val expected = (221.325 / 1.066 - 1.013) / 1.013 * 11
        assertTrue(abs(total - expected) < expected * 0.01, "total $total, expected about $expected")
        assertTrue(total < 11.0 * 220)
    }

    @Test
    fun tracksUsageForAirTopUp() {
        val r = planBlend(Cylinder(12.0, 50.0, BreathingGas.AIR), Fill(BreathingGas.AIR, 200.0), gases)
        assertTrue(r.ok)
        val airUsed = assertNotNull(r.litresUsed["Air"])
        val ideal = 150.0 * 12
        assertTrue(airUsed > ideal * 0.9 && airUsed < ideal, "air $airUsed")
    }

    @Test
    fun stepsAddUpToTheTotals() {
        val r = planBlend(Cylinder(11.0, 0.0, BreathingGas.AIR), Fill(pct(21.0, 35.0), 200.0), gases)
        assertTrue(r.ok)
        val adds = r.steps.filterIsInstance<BlendStep.Add>()
        for ((name, litres) in r.litresUsed) {
            assertTrue(abs(litres - adds.filter { it.source.name == name }.sumOf { it.litres }) < 1e-6)
        }
        adds.forEach {
            val ideal = it.addedBar * 11
            assertTrue(it.litres > ideal * 0.85 && it.litres < ideal * 1.05, "step $it")
        }
    }
}
