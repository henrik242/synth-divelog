package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.ATM_BAR
import kotlin.math.ceil
import kotlin.math.max

/**
 * Breathing [usedLitres] of free gas at 1 atm from [cylinder], by the real-gas
 * [Compressibility] of its mix. [availableLitres] is what it gives before the gauge reads 0,
 * [usableLitres] before it reads [reserveBar]. [endBar] is the gauge pressure left; short of
 * gas, it is the shortfall below 0 as bar of a near-ideal gas.
 */
data class CylinderUse(
    val cylinder: Cylinder,
    val usedLitres: Double,
    val reserveBar: Double,
    val availableLitres: Double,
    val usableLitres: Double,
    val endBar: Double,
) {
    val usedBar: Double get() = cylinder.pressureBar - endBar
    val enough: Boolean get() = usedLitres <= availableLitres
    val keepsReserve: Boolean get() = usedLitres <= usableLitres

    /**
     * How many cylinders like this one, each with the same fill and each keeping its reserve,
     * the gas takes; null when the reserve leaves nothing to breathe.
     */
    val cylindersNeeded: Int?
        get() = if (usableLitres <= 0) null else max(1, ceil(usedLitres / usableLitres - 1e-9).toInt())
}

fun cylinderUse(cylinder: Cylinder, usedLitres: Double, reserveBar: Double = 0.0): CylinderUse {
    val o2 = cylinder.gas.o2 / 1000.0
    val he = cylinder.gas.he / 1000.0
    fun ideal(gaugeBar: Double) = Compressibility.idealBar(gaugeBar, o2, he)
    // Ideal bar is proportional to the free gas the cylinder holds: water litres x ideal bar / 1 atm.
    fun litres(idealBar: Double) = cylinder.volumeLitres * idealBar / ATM_BAR
    val start = ideal(cylinder.pressureBar)
    val empty = ideal(0.0)
    val end = start - usedLitres * ATM_BAR / cylinder.volumeLitres
    // Short of gas, the shortfall as bar of a near-ideal gas.
    val endBar = if (end >= empty) Compressibility.gaugeBar(end, o2, he) else end - empty
    val usable = max(0.0, litres(start - ideal(reserveBar.coerceIn(0.0, cylinder.pressureBar))))
    return CylinderUse(cylinder, usedLitres, reserveBar, litres(start - empty), usable, endBar)
}

/**
 * Several cylinders of one mix as one: their volumes added, at the pressure they would
 * equalise to if joined, so the gas in them is the sum.
 */
fun combined(cylinders: List<Cylinder>): Cylinder {
    require(cylinders.isNotEmpty()) { "No cylinders to combine" }
    val gas = cylinders.first().gas
    require(cylinders.all { it.gas == gas }) { "Cylinders of different mixes" }
    if (cylinders.size == 1) return cylinders.first()
    val o2 = gas.o2 / 1000.0
    val he = gas.he / 1000.0
    val volume = cylinders.sumOf { it.volumeLitres }
    val idealBar = cylinders.sumOf { Compressibility.idealBar(it.pressureBar, o2, he) * it.volumeLitres } / volume
    return Cylinder(volume, Compressibility.gaugeBar(idealBar, o2, he), gas)
}
