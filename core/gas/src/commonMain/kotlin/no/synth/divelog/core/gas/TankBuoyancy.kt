package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.ATM_BAR
import no.synth.divelog.core.model.units.BAR_PER_PSI
import no.synth.divelog.core.model.units.LBS_PER_KG
import no.synth.divelog.core.model.units.LITRES_PER_CUFT
import no.synth.divelog.core.model.units.PSI_PER_ATM
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

private const val KG_PER_LITRE_IN_LBS_PER_CUFT = LBS_PER_KG * LITRES_PER_CUFT

// kg/litre: chrome-moly steel, 6061-T6 aluminium, air at 15 C and 1 atm.
private const val STEEL_DENSITY = 7.85
private const val ALU_DENSITY = 2.7
private const val AIR_DENSITY = 0.001225

/** Typical DIN/yoke valve, kg. */
private const val VALVE_KG = 0.9

/** A doubles manifold on top of its two valves, kg. */
private const val MANIFOLD_KG = 1.5

enum class TankMetal { STEEL, ALUMINIUM }

/** One cylinder rated in water volume and working pressure, with its weight (no valve). */
data class MetricTank(val litres: Double, val bar: Double, val kg: Double) {
    fun toImperial() = ImperialTank(litres / LITRES_PER_CUFT * bar / ATM_BAR, bar / BAR_PER_PSI, kg * LBS_PER_KG)
}

/**
 * One cylinder rated in free gas at working pressure, with its weight (no valve). The rating is
 * nominal, ideal gas at 1 atm: inner volume x psi / 14.7, as US cylinders are labelled.
 */
data class ImperialTank(val cubicFeet: Double, val psi: Double, val lbs: Double) {
    val innerCubicFeet: Double get() = cubicFeet / psi * PSI_PER_ATM

    fun toMetric() = MetricTank(innerCubicFeet * LITRES_PER_CUFT, psi * BAR_PER_PSI, lbs / LBS_PER_KG)
}

/** [doubles] means two cylinders on a manifold. */
data class TankSetup(
    val metal: TankMetal = TankMetal.STEEL,
    val saltWater: Boolean = true,
    val valve: Boolean = true,
    val doubles: Boolean = false,
)

/** One line of the worked calculation; [result] is the value it arrives at, if any. */
data class CalculationStep(val text: String, val result: String? = null)

/**
 * Buoyancy in water (positive floats, negative sinks) with an empty and a full cylinder,
 * rounded to 0.1. [totalKg] and [totalLitres] cover both cylinders of a doubles set.
 */
data class Buoyancy(
    val emptyKg: Double,
    val fullKg: Double,
    val emptyLbs: Double,
    val fullLbs: Double,
    val totalKg: Double,
    val totalLitres: Double,
    val steps: List<CalculationStep>,
) {
    /** Weight of the gas in a full fill. */
    val gasKg: Double get() = emptyKg - fullKg
}

/**
 * Archimedes: the cylinder displaces its metal, valve and inner volume of water, and weighs
 * its metal, valve and gas.
 */
fun buoyancy(tank: MetricTank, setup: TankSetup): Buoyancy {
    val metal = if (setup.metal == TankMetal.ALUMINIUM) ALU_DENSITY else STEEL_DENSITY
    val water = waterOf(setup).kgPerLitre
    val cylinders = if (setup.doubles) 2 else 1
    val litres = tank.litres * cylinders
    val kg = tank.kg * cylinders
    val valve = when {
        !setup.valve -> 0.0
        setup.doubles -> VALVE_KG * 2 + MANIFOLD_KG
        else -> VALVE_KG
    }

    val metalVolume = kg / metal
    val valveVolume = valve / STEEL_DENSITY
    val displaced = (metalVolume + valveVolume + litres) * water
    val z = airZ(tank.bar)
    val freeAir = litres * freeVolumes(tank.bar)
    val air = AIR_DENSITY * freeAir
    val empty = displaced - kg - valve
    val full = empty - air

    val steps = buildList {
        add(
            CalculationStep(
                "Steel has a density of ${raw(STEEL_DENSITY)} kg/liter" +
                    if (metal != STEEL_DENSITY) ", and aluminium is ${raw(ALU_DENSITY)} kg/liter" else "",
            ),
        )
        add(CalculationStep("The volume of the tank metal is ${raw(kg)} kg / ${raw(metal)}", "${num(metalVolume)} liters"))
        if (valve != 0.0) {
            add(CalculationStep("The volume of the valve is ${num(valve)} kg / ${raw(STEEL_DENSITY)}", "${num(valveVolume)} liters"))
        }
        add(CalculationStep(waterLine(setup.saltWater, "${raw(water)} kg/liter")))
        val plusValve = if (valve != 0.0) " + ${num(valveVolume)}" else ""
        add(
            CalculationStep(
                "Total weight in water: (${raw(litres)} + ${num(metalVolume)}$plusValve) x ${raw(water)}",
                "${num(displaced)} kg",
            ),
        )
        add(CalculationStep(zLine(tank.bar, z)))
        add(
            CalculationStep(
                "Free air in a full tank: ${raw(litres)} liters x (${num(tank.bar + ATM_BAR)} bar / ${num(z)} - ${raw(ATM_BAR)} bar) / ${raw(ATM_BAR)}",
                "${num(freeAir)} liters",
            ),
        )
        add(CalculationStep("Air has a density of ${raw(AIR_DENSITY)} kg/liter at 1 atm"))
        add(CalculationStep("The air in a full tank weighs ${raw(AIR_DENSITY)} x ${num(freeAir)} liters", "${num(air)} kg"))
        val minusValve = if (valve != 0.0) " - ${num(valve)}" else ""
        add(CalculationStep("Tank buoyancy when empty: ${num(displaced)} - ${raw(kg)}$minusValve", "${num(round1(empty))} kg"))
        add(
            CalculationStep(
                "Tank buoyancy when full: ${num(displaced)} - ${raw(kg)}$minusValve - ${num(air)}",
                "${num(round1(full))} kg",
            ),
        )
    }
    return Buoyancy(
        round1(empty), round1(full), round1(empty * LBS_PER_KG), round1(full * LBS_PER_KG),
        round1(kg), round1(litres), steps,
    )
}

/** As [buoyancy] for a metric tank, worked in pounds and cubic feet. */
fun buoyancy(tank: ImperialTank, setup: TankSetup): Buoyancy {
    val metal = (if (setup.metal == TankMetal.ALUMINIUM) ALU_DENSITY else STEEL_DENSITY) * KG_PER_LITRE_IN_LBS_PER_CUFT
    val steel = STEEL_DENSITY * KG_PER_LITRE_IN_LBS_PER_CUFT
    val water = waterOf(setup).kgPerLitre * KG_PER_LITRE_IN_LBS_PER_CUFT
    val air = AIR_DENSITY * KG_PER_LITRE_IN_LBS_PER_CUFT
    val cylinders = if (setup.doubles) 2 else 1
    val cubicFeet = tank.cubicFeet * cylinders
    val lbs = tank.lbs * cylinders
    val valve = when {
        !setup.valve -> 0.0
        setup.doubles -> (VALVE_KG * 2 + MANIFOLD_KG) * LBS_PER_KG
        else -> VALVE_KG * LBS_PER_KG
    }

    val innerVolume = tank.innerCubicFeet * cylinders
    val metalVolume = lbs / metal
    val valveVolume = valve / steel
    val displaced = (innerVolume + metalVolume + valveVolume) * water
    val bar = tank.psi * BAR_PER_PSI
    val z = airZ(bar)
    val freeAir = innerVolume * freeVolumes(bar)
    val gas = air * freeAir
    val empty = displaced - lbs - valve
    val full = empty - gas

    val steps = buildList {
        add(CalculationStep("Air has a pressure of ${num(PSI_PER_ATM)} psi at 1 atm"))
        add(CalculationStep("Tank inner volume is ${raw(cubicFeet)} cuft / ${raw(tank.psi)} psi x ${num(PSI_PER_ATM)}", "${num(innerVolume)} cuft"))
        add(
            CalculationStep(
                "Steel has a density of ${num(steel)} lbs/cuft" +
                    if (metal != steel) ", and aluminium is ${num(metal)} lbs/cuft" else "",
            ),
        )
        add(CalculationStep("The volume of the tank metal is ${raw(lbs)} lbs / ${num(metal)}", "${num(metalVolume)} cuft"))
        if (valve != 0.0) {
            add(CalculationStep("The volume of the valve is ${num(valve)} lbs / ${num(steel)}", "${num(valveVolume)} cuft"))
        }
        add(CalculationStep(waterLine(setup.saltWater, "${num(water)} lbs/cuft")))
        val plusValve = if (valve != 0.0) " + ${num(valveVolume)}" else ""
        add(
            CalculationStep(
                "Total weight in water: (${num(innerVolume)} + ${num(metalVolume)}$plusValve) x ${num(water)}",
                "${num(displaced)} lbs",
            ),
        )
        add(CalculationStep(zLine(bar, z) + " (${num(tank.psi, 0)} psi)"))
        add(
            CalculationStep(
                "Free air in a full tank: ${num(innerVolume)} cuft x (${num(bar + ATM_BAR)} bar / ${num(z)} - ${raw(ATM_BAR)} bar) / ${raw(ATM_BAR)}",
                "${num(freeAir)} cuft",
            ),
        )
        add(CalculationStep("Air has a density of ${num(air)} lbs/cuft at 1 atm"))
        add(CalculationStep("The air in a full tank weighs ${num(air)} x ${num(freeAir)} cuft", "${num(gas)} lbs"))
        val minusValve = if (valve != 0.0) " - ${num(valve)}" else ""
        add(CalculationStep("Tank buoyancy when empty: ${num(displaced)} - ${raw(lbs)}$minusValve", "${num(round1(empty))} lbs"))
        add(
            CalculationStep(
                "Tank buoyancy when full: ${num(displaced)} - ${raw(lbs)}$minusValve - ${num(gas)}",
                "${num(round1(full))} lbs",
            ),
        )
    }
    val metric = tank.toMetric()
    return Buoyancy(
        round1(empty / LBS_PER_KG), round1(full / LBS_PER_KG), round1(empty), round1(full),
        round1(metric.kg * cylinders), round1(metric.litres * cylinders), steps,
    )
}

private fun waterOf(setup: TankSetup) = if (setup.saltWater) Water.SALT else Water.FRESH

private fun waterLine(salt: Boolean, density: String) =
    if (salt) "The density of salt water is $density" else "The density of fresh water is $density"

/** Z of air at [gaugeBar]. */
private fun airZ(gaugeBar: Double): Double = Compressibility.z(BreathingGas.AIR, gaugeBar + ATM_BAR)

private fun zLine(gaugeBar: Double, z: Double) =
    "Air at ${num(gaugeBar + ATM_BAR)} bar absolute is less compressible than an ideal gas: Z = ${num(z)}"

/**
 * Free air at 1 atm that a full cylinder holds beyond an empty one (which still holds 1 atm),
 * per volume of cylinder.
 */
private fun freeVolumes(gaugeBar: Double): Double = ((gaugeBar + ATM_BAR) / airZ(gaugeBar) - ATM_BAR) / ATM_BAR

private fun round1(v: Double): Double = roundHalfUp(v, 1)

/** Up to [decimals] decimals, trailing zeros dropped: 7.85, 1.8471, 29. */
internal fun num(v: Double, decimals: Int = 4): String {
    val factor = 10.0.pow(decimals).toLong()
    val scaled = floor(abs(v) * factor + 0.5).toLong()
    val sign = if (v < 0 && scaled != 0L) "-" else ""
    val whole = scaled / factor
    val frac = (scaled % factor).toString().padStart(decimals, '0').trimEnd('0')
    return if (frac.isEmpty()) "$sign$whole" else "$sign$whole.$frac"
}

/** Inputs and constants as given, not rounded to four decimals. */
private fun raw(v: Double): String = num(v, 9)

/** A common cylinder, per cylinder; [doubles] presets are a manifolded pair. */
data class TankPreset(
    val name: String,
    val group: String,
    val metric: MetricTank?,
    val imperial: ImperialTank?,
    val metal: TankMetal,
    val doubles: Boolean = false,
)

val TANK_PRESETS: List<TankPreset> = run {
    fun m(group: String, name: String, l: Double, bar: Double, kg: Double, metal: TankMetal = TankMetal.STEEL, doubles: Boolean = false) =
        TankPreset(name, group, MetricTank(l, bar, kg), null, metal, doubles)
    fun i(group: String, name: String, cuft: Double, psi: Double, lbs: Double, metal: TankMetal) =
        TankPreset(name, group, null, ImperialTank(cuft, psi, lbs), metal)
    val steel = "Metric steel"
    val alu = "Metric aluminium"
    val stage = "Metric stage/pony"
    val iSteel = "Imperial steel"
    val iAlu = "Imperial aluminium"
    val iStage = "Imperial stage/pony"
    listOf(
        m(steel, "10 L 200 bar (12.6 kg)", 10.0, 200.0, 12.6),
        m(steel, "12 L 200 bar (14.0 kg)", 12.0, 200.0, 14.0),
        m(steel, "15 L 200 bar (16.5 kg)", 15.0, 200.0, 16.5),
        m(steel, "18 L 200 bar (19.8 kg)", 18.0, 200.0, 19.8),
        m(steel, "12 L 232 bar (14.5 kg)", 12.0, 232.0, 14.5),
        m(steel, "7 L 300 bar (10.5 kg)", 7.0, 300.0, 10.5),
        m(steel, "10 L 300 bar (14.0 kg)", 10.0, 300.0, 14.0),
        m(steel, "Twin 12 L 200 bar (14.0 kg each)", 12.0, 200.0, 14.0, doubles = true),
        m(steel, "Twin 12 L 232 bar (14.5 kg each)", 12.0, 232.0, 14.5, doubles = true),
        m(alu, "S80 (11.1 L 207 bar, 14.2 kg)", 11.1, 207.0, 14.2, TankMetal.ALUMINIUM),
        m(alu, "S72 (10.0 L 207 bar, 13.4 kg)", 10.0, 207.0, 13.4, TankMetal.ALUMINIUM),
        m(stage, "3 L 200 bar pony (3.8 kg)", 3.0, 200.0, 3.8),
        m(stage, "5 L 200 bar stage (5.5 kg)", 5.0, 200.0, 5.5),
        m(stage, "7 L 200 bar stage (7.5 kg)", 7.0, 200.0, 7.5),
        i(iSteel, "HP80 (80 cuft, 3500 psi, 28.5 lbs)", 80.0, 3500.0, 28.5, TankMetal.STEEL),
        i(iSteel, "HP85 (85 cuft, 3442 psi, 33 lbs)", 85.0, 3442.0, 33.0, TankMetal.STEEL),
        i(iSteel, "HP100 (100 cuft, 3442 psi, 38 lbs)", 100.0, 3442.0, 38.0, TankMetal.STEEL),
        i(iSteel, "HP117 (117 cuft, 3442 psi, 44 lbs)", 117.0, 3442.0, 44.0, TankMetal.STEEL),
        i(iSteel, "HP120 (120 cuft, 3442 psi, 43 lbs)", 120.0, 3442.0, 43.0, TankMetal.STEEL),
        i(iAlu, "AL63 (63 cuft, 3000 psi, 26 lbs)", 63.0, 3000.0, 26.0, TankMetal.ALUMINIUM),
        i(iAlu, "AL80 (80 cuft, 3000 psi, 31.4 lbs)", 80.0, 3000.0, 31.4, TankMetal.ALUMINIUM),
        i(iAlu, "AL100 (100 cuft, 3300 psi, 39 lbs)", 100.0, 3300.0, 39.0, TankMetal.ALUMINIUM),
        i(iStage, "AL19 pony (19 cuft, 3000 psi, 11 lbs)", 19.0, 3000.0, 11.0, TankMetal.ALUMINIUM),
        i(iStage, "AL30 stage (30 cuft, 3000 psi, 14.5 lbs)", 30.0, 3000.0, 14.5, TankMetal.ALUMINIUM),
        i(iStage, "AL40 stage (40 cuft, 3000 psi, 17 lbs)", 40.0, 3000.0, 17.0, TankMetal.ALUMINIUM),
    )
}
