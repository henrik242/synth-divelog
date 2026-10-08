package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The scuba-tools web blender's test suite, case for case. */
class GasBlenderTest {
    private val air = SourceGas("Air", 21.0, 0.0)
    private val o2 = SourceGas("O2", 100.0, 0.0)
    private val helium = SourceGas("Helium", 0.0, 100.0)
    private val ean32 = SourceGas("Nitrox 32", 32.0, 0.0)
    private val tx1070 = SourceGas("10/70", 10.0, 70.0)
    private val standard = listOf(air, o2, helium, ean32, tx1070)

    private fun blend(
        volume: Double, startBar: Double, startO2: Double, startHe: Double,
        targetO2: Double, targetHe: Double, targetBar: Double,
        gases: List<SourceGas> = standard,
    ) = planBlend(Cylinder(volume, startBar, startO2, startHe), Fill(targetO2, targetHe, targetBar), gases)

    private fun empty(targetO2: Double, targetHe: Double, targetBar: Double, gases: List<SourceGas> = standard, volume: Double = 12.0) =
        blend(volume, 0.0, 0.0, 0.0, targetO2, targetHe, targetBar, gases)

    private val BlendPlan.drain get() = steps.firstOrNull { it is BlendStep.Drain }
    private fun BlendPlan.addOf(name: String) = steps.firstOrNull { it is BlendStep.Add && it.gas.name == name }

    // Input validation

    @Test
    fun rejectsTargetWithO2PlusHeOver100() {
        val r = blend(12.0, 0.0, 21.0, 0.0, 60.0, 50.0, 200.0)
        assertTrue(!r.ok)
        assertEquals(BlendProblem.MIX_OVER_100, r.problem)
    }

    // Empty tank

    @Test
    fun blends1845FromEmpty() {
        val r = empty(18.0, 45.0, 200.0)
        assertTrue(r.ok)
        assertClose(18.0, r.result.o2)
        assertClose(45.0, r.result.he)
        assertClose(200.0, r.result.pressureBar)
        assertTrue(r.steps.isNotEmpty())
    }

    @Test
    fun blendsNormoxic2135FromEmpty() {
        val r = empty(21.0, 35.0, 200.0)
        assertTrue(r.ok)
        assertClose(21.0, r.result.o2)
        assertClose(35.0, r.result.he)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun blendsNitrox32FromEmpty() {
        val r = empty(32.0, 0.0, 200.0)
        assertTrue(r.ok)
        assertClose(32.0, r.result.o2)
        assertEquals(0.0, r.result.he)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun blendsAirFromEmpty() {
        val r = empty(21.0, 0.0, 200.0)
        assertTrue(r.ok)
        assertClose(21.0, r.result.o2)
        assertEquals(0.0, r.result.he)
        assertEquals(200.0, r.result.pressureBar)
        assertEquals("Air", (r.steps.single() as BlendStep.Add).gas.name)
    }

    // Partial tank topping

    @Test
    fun topsUpAirFrom50To200() {
        val r = blend(12.0, 50.0, 21.0, 0.0, 21.0, 0.0, 200.0)
        assertTrue(r.ok)
        assertClose(21.0, r.result.o2)
        assertEquals(200.0, r.result.pressureBar)
        assertClose(150.0, (r.steps.single() as BlendStep.Add).addedBar)
    }

    @Test
    fun topsUp1845FromPartial() {
        val r = blend(12.0, 100.0, 18.0, 45.0, 18.0, 45.0, 200.0)
        assertTrue(r.ok)
        assertClose(18.0, r.result.o2)
        assertClose(45.0, r.result.he)
        assertEquals(200.0, r.result.pressureBar)
    }

    // Draining

    @Test
    fun drainsWhenStartO2IsTooHigh() {
        val r = blend(12.0, 100.0, 32.0, 0.0, 21.0, 0.0, 200.0)
        if (r.ok) {
            assertTrue(r.steps.isNotEmpty())
            assertClose(21.0, r.result.o2)
        } else {
            assertEquals(BlendProblem.OFF_TARGET, r.problem)
        }
    }

    @Test
    fun drainsEnoughForNitrox32To1835() {
        val r = blend(11.0, 110.0, 32.0, 0.0, 18.0, 35.0, 220.0, listOf(air, o2, helium))
        assertTrue(r.ok)
        val drain = assertNotNull(r.drain)
        assertTrue(drain.toBar < 110)
        assertClose(18.0, r.result.o2)
        assertClose(35.0, r.result.he)
        assertClose(220.0, r.result.pressureBar)
    }

    @Test
    fun drainsWhenStartHeIsTooHigh() {
        val r = blend(12.0, 100.0, 18.0, 50.0, 21.0, 35.0, 200.0)
        assertTrue(r.ok)
        assertClose(35.0, r.result.he)
        assertClose(21.0, r.result.o2)
    }

    @Test
    fun drainsCompletelyWhenIncompatible() {
        val r = blend(12.0, 150.0, 50.0, 40.0, 21.0, 0.0, 200.0)
        assertTrue(r.ok)
        assertNotNull(r.drain)
    }

    // Deep trimix

    @Test
    fun blends1070() {
        val r = empty(10.0, 70.0, 200.0)
        assertTrue(r.ok)
        assertClose(10.0, r.result.o2)
        assertClose(70.0, r.result.he)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun blends1265FromEmpty() {
        val r = empty(12.0, 65.0, 200.0)
        assertTrue(r.ok)
        assertClose(12.0, r.result.o2)
        assertClose(65.0, r.result.he)
    }

    // Travel mixes

    @Test
    fun blendsShallowTravel2130() {
        val r = empty(21.0, 30.0, 200.0)
        assertTrue(r.ok)
        assertClose(21.0, r.result.o2)
        assertClose(30.0, r.result.he)
    }

    @Test
    fun blendsBalanced2525() {
        val r = empty(25.0, 25.0, 200.0)
        assertTrue(r.ok)
        assertClose(25.0, r.result.o2)
        assertClose(25.0, r.result.he)
    }

    // Partial pressures

    @Test
    fun partialPressuresFor1845At200() {
        val r = empty(18.0, 45.0, 200.0)
        assertTrue(r.ok)
        assertClose(36.0, r.result.o2 / 100 * r.result.pressureBar)
        assertClose(90.0, r.result.he / 100 * r.result.pressureBar)
    }

    @Test
    fun partialPressuresHoldWhenToppingUp() {
        val r = blend(12.0, 50.0, 21.0, 35.0, 21.0, 35.0, 200.0)
        assertTrue(r.ok)
        assertClose(42.0, r.result.o2 / 100 * r.result.pressureBar)
        assertClose(70.0, r.result.he / 100 * r.result.pressureBar)
    }

    // Edge cases

    @Test
    fun blends1413At100To1845At220WithoutDraining() {
        val r = blend(11.0, 100.0, 14.0, 13.0, 18.0, 45.0, 220.0)
        assertTrue(r.ok)
        assertTrue(r.steps.isNotEmpty())
        assertTrue(r.steps[0] !is BlendStep.Drain)
        assertClose(18.0, r.result.o2)
        assertClose(45.0, r.result.he)
        assertClose(220.0, r.result.pressureBar)
    }

    @Test
    fun handlesZeroStartPressure() {
        val r = blend(12.0, 0.0, 21.0, 0.0, 21.0, 0.0, 200.0)
        assertTrue(r.ok)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun noStepsWhenAlreadyAtTarget() {
        val r = blend(12.0, 200.0, 21.0, 35.0, 21.0, 35.0, 200.0)
        assertTrue(r.ok)
        assertEquals(0, r.steps.size)
    }

    @Test
    fun handlesVerySmallPressureDifference() {
        val r = blend(12.0, 199.0, 21.0, 0.0, 21.0, 0.0, 200.0)
        assertTrue(r.ok)
    }

    // Limited gas availability

    @Test
    fun worksWithOnlyAirAndO2() {
        val r = empty(32.0, 0.0, 200.0, listOf(air, o2))
        assertClose(32.0, r.result.o2)
        assertEquals(0.0, r.result.he)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun cannotReachHeliumWithoutHelium() {
        val r = empty(18.0, 45.0, 200.0, listOf(air, o2))
        assertTrue(abs(r.result.he - 45.0) >= 0.5)
    }

    // Step verification

    @Test
    fun stepsAreConsistentForTrimix() {
        val r = empty(18.0, 45.0, 200.0)
        assertTrue(r.ok)
        assertTrue(r.steps.isNotEmpty())
        r.steps.forEach { s ->
            when (s) {
                is BlendStep.Drain -> assertTrue(s.toBar < s.fromBar)
                is BlendStep.Add -> {
                    assertTrue(s.toBar > s.fromBar)
                    assertClose(s.toBar - s.fromBar, s.addedBar, digits = 1)
                }
            }
        }
    }

    @Test
    fun everyAdditionRaisesPressure() {
        val r = empty(21.0, 35.0, 200.0)
        assertTrue(r.ok)
        r.steps.filterIsInstance<BlendStep.Add>().forEach {
            assertTrue(it.toBar > it.fromBar)
            assertTrue(it.addedBar > 0)
        }
    }

    // Real-world scenarios

    @Test
    fun topsUp1937At50To1840At220WithAirNotNitrox32() {
        val r = blend(11.0, 50.0, 19.0, 37.0, 18.0, 40.0, 220.0, listOf(air, o2, helium, ean32))
        assertTrue(r.ok)
        assertClose(18.0, r.result.o2)
        assertClose(40.0, r.result.he)
        assertClose(220.0, r.result.pressureBar)
        assertNotNull(r.addOf("Air"))
    }

    @Test
    fun blendsBottomGas1455InTwin12s() {
        val r = empty(14.0, 55.0, 220.0, volume = 24.0)
        assertTrue(r.ok)
        assertClose(14.0, r.result.o2)
        assertClose(55.0, r.result.he)
    }

    @Test
    fun blendsEan50Deco() {
        val r = empty(50.0, 0.0, 200.0, listOf(air, o2), volume = 7.0)
        assertClose(50.0, r.result.o2)
        assertEquals(0.0, r.result.he)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun convertsAirTo1845() {
        val r = blend(12.0, 100.0, 21.0, 0.0, 18.0, 45.0, 200.0)
        assertClose(45.0, r.result.he)
        assertClose(18.0, r.result.o2)
        assertEquals(200.0, r.result.pressureBar)
        assertTrue(r.steps[0] is BlendStep.Drain)
    }

    // Accuracy and tolerance

    @Test
    fun withinTolerance() {
        val r = empty(18.0, 45.0, 200.0)
        assertTrue(r.ok)
        assertClose(45.0, r.result.he)
        assertClose(18.0, r.result.o2)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun okMatchesTolerance1937At70To1540At220() {
        val r = blend(11.0, 70.0, 19.0, 37.0, 15.0, 40.0, 220.0)
        val o2Error = abs(r.result.o2 - 15.0)
        val heError = abs(r.result.he - 40.0)
        val barError = abs(r.result.pressureBar - 220.0)
        if (r.ok) {
            assertTrue(o2Error <= 0.5 && heError <= 0.5 && barError <= 1)
        } else {
            assertTrue(o2Error > 0.5 || heError > 0.5 || barError > 1)
        }
    }

    // Oxygen toxicity

    @Test
    fun blendsEan80Deco() {
        val r = empty(80.0, 0.0, 200.0, volume = 7.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 80) <= 0.5)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun fillsPureO2() {
        val r = empty(100.0, 0.0, 200.0, listOf(o2), volume = 7.0)
        assertTrue(r.ok)
        assertEquals(100.0, r.result.o2)
        assertEquals(1, r.steps.size)
    }

    @Test
    fun blendsHypoxic1070Accurately() {
        val r = empty(10.0, 70.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 10) <= 0.5)
        assertTrue(abs(r.result.he - 70) <= 1.0)
    }

    // Technical diving precision

    @Test
    fun o2WithinHalfPointForTrimix() {
        val r = empty(18.0, 45.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 18) <= 0.5)
        assertTrue(abs(r.result.he - 45) <= 2.0)
    }

    @Test
    fun pressureWithinOneBar() {
        val r = empty(21.0, 35.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.pressureBar - 200) <= 1)
    }

    @Test
    fun ean36WithinHalfPoint() {
        val r = empty(36.0, 0.0, 200.0, listOf(air, o2))
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 36) <= 0.5)
    }

    // Mix conversions

    @Test
    fun converts2135At100To1845At200() {
        val r = blend(12.0, 100.0, 21.0, 35.0, 18.0, 45.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 18) <= 0.5)
        assertTrue(abs(r.result.he - 45) <= 2.0)
        assertEquals(200.0, r.result.pressureBar)
    }

    @Test
    fun convertsAirToEan32() {
        val r = blend(12.0, 50.0, 21.0, 0.0, 32.0, 0.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 32) <= 0.5)
    }

    @Test
    fun convertsEan32At150ToAirAt200() {
        // Air cannot dilute 32 % down to 21 % without a full drain; only check when it claims success.
        val r = blend(12.0, 150.0, 32.0, 0.0, 21.0, 0.0, 200.0)
        if (r.ok) assertTrue(abs(r.result.o2 - 21) <= 0.5)
    }

    // High-pressure cylinders

    @Test
    fun fills300Bar() {
        val r = empty(18.0, 45.0, 300.0)
        assertTrue(r.ok)
        assertEquals(300.0, r.result.pressureBar)
        assertTrue(abs(r.result.o2 - 18) <= 0.5)
    }

    @Test
    fun fills232Bar() {
        val r = empty(21.0, 35.0, 232.0)
        assertTrue(r.ok)
        assertEquals(232.0, r.result.pressureBar)
    }

    // Impure source gases

    @Test
    fun industrialO2At99Point5() {
        val r = empty(32.0, 0.0, 200.0, listOf(air, SourceGas("Industrial O2", 99.5, 0.0), helium))
        if (r.ok) assertTrue(abs(r.result.o2 - 32) <= 1.0)
    }

    @Test
    fun commercialHeliumWithTraceO2() {
        val r = empty(10.0, 70.0, 200.0, listOf(air, o2, SourceGas("Commercial He", 0.5, 99.5)))
        if (r.ok) assertTrue(abs(r.result.he - 70) <= 2.0)
    }

    // Multiple nitrox banks

    @Test
    fun picksEan32FromSeveralNitroxBanks() {
        val banks = listOf(
            air, SourceGas("EAN28", 28.0, 0.0), SourceGas("EAN32", 32.0, 0.0), SourceGas("EAN36", 36.0, 0.0), o2,
        )
        val r = empty(32.0, 0.0, 200.0, banks)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 32) <= 0.5)
        assertNotNull(r.addOf("EAN32"))
    }

    // Partial-pressure edge cases

    @Test
    fun oneBarResidual() {
        val r = blend(12.0, 1.0, 21.0, 0.0, 32.0, 0.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 32) <= 0.5)
    }

    @Test
    fun oddTargetPressure187() {
        val r = empty(18.0, 45.0, 187.0)
        assertTrue(r.ok)
        assertClose(187.0, r.result.pressureBar)
    }

    // Rounding

    @Test
    fun partialPressuresSumToFinalPressure() {
        val r = empty(18.0, 45.0, 200.0)
        val p = r.result.pressureBar
        val total = r.result.o2 / 100 * p + r.result.he / 100 * p + (100 - r.result.o2 - r.result.he) / 100 * p
        assertTrue(abs(total - p) <= 0.2)
    }

    // Sequential fills

    @Test
    fun topsUpSameCylinderTwice() {
        val first = empty(21.0, 35.0, 100.0)
        assertTrue(first.ok)
        val r = blend(12.0, first.result.pressureBar, first.result.o2, first.result.he, 21.0, 35.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 21) <= 0.5)
        assertTrue(abs(r.result.he - 35) <= 2.0)
    }

    // Extreme mixes

    @Test
    fun blendsVeryLean884() {
        val r = empty(8.0, 84.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 8) <= 0.5)
        assertTrue(abs(r.result.he - 84) <= 2.0)
    }

    @Test
    fun blendsRichTravel3030() {
        val r = empty(30.0, 30.0, 200.0)
        assertTrue(r.ok)
        assertTrue(abs(r.result.o2 - 30) <= 0.5)
        assertTrue(abs(r.result.he - 30) <= 2.0)
    }

    // Drain and blend

    @Test
    fun blends1845From3210AtSamePressure() {
        val r = blend(11.0, 220.0, 32.0, 10.0, 18.0, 45.0, 220.0, listOf(air, o2, helium))
        assertTrue(r.ok)
        assertClose(18.0, r.result.o2)
        assertClose(45.0, r.result.he)
        assertClose(220.0, r.result.pressureBar)
        assertNotNull(r.drain)
        assertNotNull(r.addOf("Helium"))
    }

    // Helium-mix source instead of pure helium

    @Test
    fun blends1845From1413UsingTrimix1070() {
        val r = blend(11.0, 113.0, 14.0, 13.0, 18.0, 45.0, 220.0, listOf(air, o2, ean32, tx1070))
        assertTrue(r.ok)
        assertClose(18.0, r.result.o2)
        assertClose(45.0, r.result.he)
        assertClose(220.0, r.result.pressureBar)
    }

    @Test
    fun blends1845From1413UsingOnlyNitrox32And1070() {
        val r = blend(11.0, 113.0, 14.0, 13.0, 18.0, 45.0, 220.0, listOf(ean32, tx1070))
        assertTrue(r.ok)
        assertClose(18.0, r.result.o2)
        assertClose(45.0, r.result.he)
        assertClose(220.0, r.result.pressureBar)
        assertEquals(0.0, assertNotNull(r.drain).toBar)
    }
}

/** Jest's toBeCloseTo: |expected - actual| < 10^-digits / 2. */
internal fun assertClose(expected: Double, actual: Double, digits: Int = 0) {
    var limit = 0.5
    repeat(digits) { limit /= 10 }
    assertTrue(abs(expected - actual) < limit, "expected $expected, was $actual")
}
