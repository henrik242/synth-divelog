package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.ATM_BAR
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CylinderUseTest {
    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String) =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, was $actual")

    private val air12 = Cylinder(12.0, 232.0, BreathingGas.AIR)

    @Test
    fun nothingUsedLeavesTheFill() {
        val use = cylinderUse(air12, 0.0)
        assertNear(232.0, use.endBar, 1e-4, "end")
        assertNear(0.0, use.usedBar, 1e-4, "used")
    }

    @Test
    fun aFullFillHoldsTheRealGasLitres() {
        // 233.3 bar absolute / Z 1.0577, less the 1 atm an empty cylinder keeps, over 1 atm, x 12 L.
        // An ideal gas would give 2747.6 L; ignoring the empty cylinder's 1 atm, 2609.5 L.
        assertNear(2597.5, cylinderUse(air12, 0.0).availableLitres, 0.5, "available")
    }

    @Test
    fun heliumHoldsLessAndOxygenMoreThanAnIdealGas() {
        fun litres(gas: BreathingGas) = cylinderUse(Cylinder(24.0, 232.0, gas), 0.0).availableLitres
        val ideal = 24.0 * 232.0 / ATM_BAR
        assertTrue(litres(BreathingGas(180, 450)) < litres(BreathingGas.AIR), "trimix")
        assertTrue(litres(BreathingGas.AIR) < ideal, "air")
        assertTrue(litres(BreathingGas.OXYGEN) > ideal, "oxygen")
    }

    @Test
    fun usingEverythingEmptiesIt() {
        val cylinder = Cylinder(24.0, 200.0, BreathingGas(210, 350))
        val all = cylinderUse(cylinder, 0.0).availableLitres
        assertNear(0.0, cylinderUse(cylinder, all).endBar, 1e-3, "end")
    }

    @Test
    fun lowPressuresAreNearlyIdeal() {
        // From 50 bar, 12 L x 20 bar of free gas leaves 29.72 bar; an ideal gas would leave 30.
        val use = cylinderUse(Cylinder(12.0, 50.0, BreathingGas.AIR), 12.0 * 20 * ATM_BAR)
        assertNear(29.72, use.endBar, 0.02, "end")
        assertTrue(use.enough)
    }

    @Test
    fun theShortfallIsIdealBar() {
        val cylinder = Cylinder(12.0, 200.0, BreathingGas.AIR)
        val all = cylinderUse(cylinder, 0.0).availableLitres
        // 12 L more than it holds is 1 atm short in a 12 L cylinder.
        val use = cylinderUse(cylinder, all + 12.0)
        assertNear(-ATM_BAR, use.endBar, 1e-6, "end")
        assertFalse(use.enough)
        assertEquals(2, use.cylindersNeeded)
    }

    @Test
    fun aReserveLeavesLessToBreathe() {
        val use = cylinderUse(air12, 1500.0, reserveBar = 232.0 / 3)
        val all = use.availableLitres
        assertTrue(use.usableLitres < all * 0.7 && use.usableLitres > all * 0.6, "usable ${use.usableLitres} of $all")
        // 1500 L leaves about 100 bar: enough and above a third.
        assertTrue(use.enough && use.keepsReserve)
        val deep = cylinderUse(air12, 2000.0, reserveBar = 232.0 / 3)
        assertTrue(deep.enough)
        assertFalse(deep.keepsReserve)
        assertEquals(2, deep.cylindersNeeded)
    }

    @Test
    fun aReserveAtOrAboveTheFillLeavesNothing() {
        val use = cylinderUse(Cylinder(12.0, 40.0, BreathingGas.AIR), 100.0, reserveBar = 50.0)
        assertEquals(0.0, use.usableLitres)
        assertNull(use.cylindersNeeded)
    }

    @Test
    fun countsTheCylindersAPlanTakes() {
        val one = cylinderUse(air12, 0.0).availableLitres
        assertEquals(1, cylinderUse(air12, 0.0).cylindersNeeded)
        assertEquals(1, cylinderUse(air12, one * 0.5).cylindersNeeded)
        assertEquals(1, cylinderUse(air12, one).cylindersNeeded)
        assertEquals(3, cylinderUse(air12, one * 3).cylindersNeeded)
        assertEquals(3, cylinderUse(air12, one * 2.1).cylindersNeeded)
    }

    @Test
    fun runningOutGoesBelowZero() {
        val use = cylinderUse(Cylinder(7.0, 200.0, BreathingGas(500)), 2000.0)
        assertFalse(use.enough)
        assertTrue(use.endBar < 0)
    }

    @Test
    fun combinedCylindersHoldTheirSum() {
        val ean50 = BreathingGas(500)
        val a = Cylinder(7.0, 200.0, ean50)
        val b = Cylinder(11.0, 150.0, ean50)
        val both = combined(listOf(a, b))
        assertEquals(18.0, both.volumeLitres)
        assertTrue(both.pressureBar > 150 && both.pressureBar < 200)
        val sum = cylinderUse(a, 0.0).availableLitres + cylinderUse(b, 0.0).availableLitres
        assertNear(sum, cylinderUse(both, 0.0).availableLitres, 0.01, "litres")
        assertEquals(a, combined(listOf(a)))
        assertEquals(Cylinder(14.0, 200.0, ean50).volumeLitres, combined(listOf(a, a)).volumeLitres)
        assertNear(200.0, combined(listOf(a, a)).pressureBar, 1e-6, "same fill")
        assertFailsWith<IllegalArgumentException> { combined(listOf(a, Cylinder(7.0, 200.0, BreathingGas.AIR))) }
    }

    private val ean50 = BreathingGas(500)
    private val small = Cylinder(7.0, 200.0, ean50)
    private val big = Cylinder(11.0, 150.0, ean50)
    @Test
    fun theFirstCylinderIsEmptiedBeforeTheNext() {
        val smallAll = cylinderUse(small, 0.0).availableLitres
        val light = usedInTurn(listOf(small, big), smallAll * 0.5)
        assertNear(smallAll * 0.5, light[0].usedLitres, 1e-9, "first")
        assertEquals(0.0, light[1].usedLitres)

        val more = usedInTurn(listOf(small, big), smallAll + 300.0)
        assertNear(0.0, more[0].endBar, 1e-3, "first empty")
        assertNear(300.0, more[1].usedLitres, 1e-9, "second")
        assertTrue(more.all { it.enough })
    }

    @Test
    fun theSetsReserveLiesInTheLastCylinders() {
        val bigAll = cylinderUse(big, 0.0).availableLitres
        // A reserve smaller than the last cylinder is all in it.
        val uses = usedInTurn(listOf(small, big), 100.0, reserveLitres = 500.0)
        assertEquals(0.0, uses[0].reserveBar)
        assertNear(500.0, uses[1].availableLitres - uses[1].usableLitres, 1e-6, "in the last")
        // A bigger one fills the last and spills into the one before.
        val deep = usedInTurn(listOf(small, big), 100.0, reserveLitres = bigAll + 200.0)
        assertNear(big.pressureBar, deep[1].reserveBar, 1e-9, "the last all reserve")
        assertNear(200.0, deep[0].availableLitres - deep[0].usableLitres, 1e-6, "the rest in the first")
    }

    @Test
    fun breathingIntoTheSetsReserveShowsInTheCylinderHoldingIt() {
        val all = listOf(small, big).sumOf { cylinderUse(it, 0.0).availableLitres }
        val reserve = cylinderUse(combined(listOf(small, big)), 0.0, 50.0).let { it.availableLitres - it.usableLitres }
        val kept = usedInTurn(listOf(small, big), all - reserve - 10.0, reserve)
        assertTrue(kept.all { it.keepsReserve })
        val into = usedInTurn(listOf(small, big), all - reserve + 10.0, reserve)
        assertTrue(into[0].keepsReserve && !into[1].keepsReserve && into[1].enough)
    }

    @Test
    fun pastAllTheGasTheLastCylinderIsShort() {
        val all = listOf(small, big).sumOf { cylinderUse(it, 0.0).availableLitres }
        val uses = usedInTurn(listOf(small, big), all + 50.0)
        assertNear(all + 50.0, uses.sumOf { it.usedLitres }, 1e-9, "total")
        assertNear(0.0, uses[0].endBar, 1e-3, "first empty")
        assertTrue(uses[0].enough)
        assertFalse(uses[1].enough)
        assertEquals(listOf(small), usedInTurn(listOf(small), 10.0).map { it.cylinder })
    }
}
