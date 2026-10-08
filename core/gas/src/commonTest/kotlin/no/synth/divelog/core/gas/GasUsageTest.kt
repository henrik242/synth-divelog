package no.synth.divelog.core.gas

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Free litres per gas. Real O2 and N2 compress more than ideal (Z < 1), so a fill takes
 * a little more than bar x volume.
 */
class GasUsageTest {
    private val gases = listOf(
        SourceGas("Air", 21.0, 0.0),
        SourceGas("O2", 100.0, 0.0),
        SourceGas("Helium", 0.0, 100.0),
    )

    @Test
    fun tracksUsageFor1845FromEmpty11L() {
        val r = planBlend(Cylinder(11.0, 0.0, 0.0, 0.0), Fill(18.0, 45.0, 220.0), gases)
        assertTrue(r.ok)
        r.steps.filterIsInstance<BlendStep.Add>().forEach { assertTrue(it.litres > 0) }
        val total = r.litresUsed.values.sum()
        val ideal = 11.0 * 220
        assertTrue(total > ideal * 0.99 && total < ideal * 1.1, "total $total")
        assertTrue(r.litresUsed.isNotEmpty())
    }

    @Test
    fun tracksUsageForAirTopUp() {
        val r = planBlend(Cylinder(12.0, 50.0, 21.0, 0.0), Fill(21.0, 0.0, 200.0), gases)
        assertTrue(r.ok)
        val airUsed = assertNotNull(r.litresUsed["Air"])
        val ideal = 150.0 * 12
        assertTrue(airUsed > ideal * 0.99 && airUsed < ideal * 1.1, "air $airUsed")
    }

    @Test
    fun everyAdditionHasUsage() {
        val r = planBlend(Cylinder(11.0, 0.0, 0.0, 0.0), Fill(21.0, 35.0, 200.0), gases)
        assertTrue(r.ok)
        r.steps.filterIsInstance<BlendStep.Add>().forEach {
            val ideal = it.addedBar * 11
            assertTrue(it.litres > ideal * 0.99 && it.litres < ideal * 1.1, "step $it")
        }
    }
}
