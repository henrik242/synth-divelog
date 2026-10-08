package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.feetToMm
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [subsurfaceMetric] and [subsurfaceImperial] are Subsurface's planner tests (tests/testplan.cpp)
 * and match its known runtimes to the second. Subsurface has no gradient-factor reference other
 * than 100/100, so the GF 30/75 bottom ceiling is checked by hand ([gf3075CeilingByHand]) and the
 * other stop tables are regression pins of this implementation, not external references.
 */
class DecoPlannerTest {
    private fun ft(v: Int) = feetToMm(v)

    // testMetric: 79 m for 30 min on 15/45 incl. a 23 m/min descent, EAN36 and O2 at pO2 1.6,
    // GF 100/100, ascent 30 ft/min and 10 ft/min the last 6 m, last stop 6 m.
    private val testMetric = DivePlan(
        levels = listOf(PlanLevel(79000, 30 * 60)),
        bottomGas = BreathingGas(150, 450),
        decoGases = listOf(BreathingGas(360), BreathingGas.OXYGEN),
        gf = GradientFactors(1.0, 1.0),
        descentRate = 23000,
        ascentRates = AscentRates(ft(30), ft(10)),
        lastStopDeep = true,
    )

    // testImperial: the same in feet, 260 ft, 75 ft/min descent, 10 ft stops.
    private val testImperial = testMetric.copy(
        levels = listOf(PlanLevel(ft(260), 30 * 60)),
        descentRate = ft(75),
        imperialStops = true,
    )

    /** Stops as depth m to minutes:seconds at the stop, run time at its end in minutes:seconds, gas. */
    private fun table(plan: DecoPlan): List<String> = plan.stops.map {
        "${it.startDepthMm / 1000} m ${clock(it.durationS)} run ${clock(it.endS)} ${it.gas.name}"
    }

    private fun clock(s: Int) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

    /** Gas and depth of each switch along the ascent. */
    private fun switches(plan: DecoPlan): List<Pair<String, Int>> =
        plan.segments.zipWithNext().filter { (a, b) -> a.gas != b.gas }.map { (_, b) -> b.gas.name to b.startDepthMm }

    @Test
    fun subsurfaceMetric() {
        val plan = DecoPlanner.plan(testMetric)
        assertNull(plan.error)
        assertEquals(109 * 60, plan.runtimeS)
        assertEquals(listOf("EAN36" to 33000, "Oxygen" to 6000), switches(plan))
    }

    @Test
    fun subsurfaceImperial() {
        val plan = DecoPlanner.plan(testImperial)
        assertNull(plan.error)
        assertEquals(111 * 60 - 2, plan.runtimeS)
        assertEquals(listOf("EAN36" to 33528, "Oxygen" to 6096), switches(plan))
    }

    @Test
    fun metricStopsEndOnWholeMinutes() {
        val plan = DecoPlanner.plan(testMetric)
        val stops = plan.stops
        assertEquals(24000, plan.firstStopMm)
        // 33 m is the minute at the EAN36 switch.
        assertEquals(listOf(33000, 24000, 21000, 18000, 15000, 12000, 9000, 6000), stops.map { it.startDepthMm })
        stops.drop(1).forEach { assertEquals(0, it.endS % 60, "stop at ${it.startDepthMm} ends at ${it.endS}") }
        assertEquals(1800, plan.bottomTimeS)
        assertEquals(109 * 60 - 1800, plan.ttsS)
    }

    @Test
    fun gf3075CeilingByHand() {
        // 40 m on air for 20 min including the descent at 18 m/min (133 s), GF 30/75, sea water.
        val plan = DecoPlanner.plan(DivePlan(listOf(PlanLevel(40000, 20 * 60)), BreathingGas.AIR))
        // Worked independently: 5.055 bar at 40 m; compartment 1 (N2 half-life 5 min) leads at
        // 3.7097 bar N2 after the Schreiner descent and 1067 s at depth. With a = 1.1696,
        // b = 0.5578, Baker's GF ceiling (P - a GF) / (GF / b + 1 - GF) at GF 0.3 gives
        // 2.7135 bar, 16.83 m below 1.013 bar at 101.04 mbar/m.
        val surface = 1.013
        val bottom = 5.055
        val descentS = 133
        var ceiling = 0.0
        for (c in 0 until Zhl16c.COMPARTMENTS) {
            val k = ln(2.0) / 60 / Zhl16c.n2HalfLife[c]
            val start = (surface - WATER_VAPOUR_BAR) * 0.781
            val inspired0 = (surface - WATER_VAPOUR_BAR) * 0.79
            val rate = (bottom - surface) / descentS * 0.79
            var p = inspired0 + rate * (descentS - 1 / k) - (inspired0 - start - rate / k) * exp(-k * descentS)
            p += ((bottom - WATER_VAPOUR_BAR) * 0.79 - p) * (1 - exp(-k * (1200 - descentS)))
            val gf = 0.3
            ceiling = maxOf(ceiling, (p - Zhl16c.n2A[c] * gf) / (gf / Zhl16c.n2B[c] + 1 - gf))
        }
        assertTrue(abs(ceiling - 2.7135) < 1e-4, "ceiling $ceiling bar")
        val ceilingMm = (ceiling - surface) * 1000 / 0.101043 // mbar over mbar per mm
        assertTrue(abs(plan.bottomCeilingMm - ceilingMm) < 10, "planner ${plan.bottomCeilingMm}, by hand $ceilingMm")

        // Regression pins. The fast leading compartment off-gasses on the way up, so the first
        // stop is above the bottom ceiling.
        assertEquals(15000, plan.firstStopMm)
        assertEquals(
            listOf(
                "15 m 1:12 run 24:00 Air", "12 m 1:40 run 26:00 Air", "9 m 3:40 run 30:00 Air",
                "6 m 5:40 run 36:00 Air", "3 m 12:40 run 49:00 Air",
            ),
            table(plan),
        )
        assertEquals(2960, plan.runtimeS)
    }

    @Test
    fun trimixWithDecoGasesAtGf5080() {
        val plan = DecoPlanner.plan(
            DivePlan(
                levels = listOf(PlanLevel(60000, 25 * 60)),
                bottomGas = BreathingGas(180, 450),
                decoGases = listOf(BreathingGas(500), BreathingGas.OXYGEN),
                gf = GradientFactors(0.5, 0.8),
            ),
        )
        assertEquals(
            listOf(
                "24 m 2:58 run 32:00 18/45", "21 m 1:00 run 33:20 EAN50", "18 m 2:20 run 36:00 EAN50",
                "15 m 1:40 run 38:00 EAN50", "12 m 4:40 run 43:00 EAN50", "9 m 6:40 run 50:00 EAN50",
                "6 m 7:40 run 58:00 Oxygen", "3 m 14:40 run 73:00 Oxygen",
            ),
            table(plan),
        )
        assertEquals(4400, plan.runtimeS)
    }

    @Test
    fun trimixWithDecoGasesAtGf3075() {
        val plan = DecoPlanner.plan(
            DivePlan(listOf(PlanLevel(60000, 25 * 60)), BreathingGas(180, 450), listOf(BreathingGas(500), BreathingGas.OXYGEN)),
        )
        assertEquals(30000, plan.firstStopMm)
        assertEquals(4820, plan.runtimeS)
        // GF low 30 starts deeper (30 m against 24 m) and takes longer than 50/80 (4400 s).
        assertEquals(10, plan.stops.size)
    }

    @Test
    fun multiLevelStopTable() {
        val plan = DecoPlanner.plan(
            DivePlan(
                levels = listOf(PlanLevel(45000, 20 * 60), PlanLevel(30000, 10 * 60), PlanLevel(15000, 10 * 60)),
                bottomGas = BreathingGas(210, 350),
                decoGases = listOf(BreathingGas(500)),
            ),
        )
        assertEquals(40 * 60, plan.bottomTimeS)
        assertEquals(
            listOf(
                "12 m 1:38 run 42:00 EAN50", "9 m 3:40 run 46:00 EAN50",
                "6 m 6:40 run 53:00 EAN50", "3 m 12:40 run 66:00 EAN50",
            ),
            table(plan),
        )
        assertEquals(3980, plan.runtimeS)
    }

    @Test
    fun noDecoDiveAscendsStraight() {
        val plan = DecoPlanner.plan(DivePlan(listOf(PlanLevel(18000, 30 * 60)), BreathingGas.AIR))
        assertNull(plan.firstStopMm)
        assertTrue(plan.stops.isEmpty())
        // 9 m/min = 150 mm/s, plus Subsurface's 2 s step at the 18 m stop level the dive starts on.
        assertEquals(30 * 60 + 18000 / 150 + 2, plan.runtimeS)
    }

    @Test
    fun lowerGradientFactorsTakeLonger() {
        val base = DivePlan(listOf(PlanLevel(40000, 25 * 60)), BreathingGas(210))
        val loose = DecoPlanner.plan(base.copy(gf = GradientFactors(0.9, 0.9)))
        val tight = DecoPlanner.plan(base.copy(gf = GradientFactors(0.3, 0.7)))
        assertTrue(tight.ttsS > loose.ttsS, "tight ${tight.ttsS} loose ${loose.ttsS}")
        assertTrue(tight.firstStopMm.let { it != null && it > (loose.firstStopMm ?: 0) })
    }

    @Test
    fun decoGasShortensTheAscent() {
        val base = DivePlan(listOf(PlanLevel(45000, 30 * 60)), BreathingGas(210, 350))
        val withEan50 = DecoPlanner.plan(base.copy(decoGases = listOf(BreathingGas(500))))
        val without = DecoPlanner.plan(base)
        assertTrue(withEan50.ttsS < without.ttsS)
        assertEquals(listOf("EAN50" to 21000), switches(withEan50))
        // One minute at the switch depth before ascending further.
        val atSwitch = withEan50.segments.first { it.gas.o2 == 500 }
        assertEquals(21000, atSwitch.startDepthMm)
        assertTrue(atSwitch.durationS >= 60)
    }

    @Test
    fun lastStopAtSixMetres() {
        val base = DivePlan(listOf(PlanLevel(40000, 30 * 60)), BreathingGas.AIR)
        assertEquals(3000, DecoPlanner.plan(base).stops.last().startDepthMm)
        assertEquals(6000, DecoPlanner.plan(base.copy(lastStopDeep = true)).stops.last().startDepthMm)
    }

    @Test
    fun multiLevelLoadsTheDeepPart() {
        val single = DecoPlanner.plan(DivePlan(listOf(PlanLevel(20000, 40 * 60)), BreathingGas.AIR))
        val multi = DecoPlanner.plan(
            DivePlan(listOf(PlanLevel(40000, 15 * 60), PlanLevel(20000, 25 * 60)), BreathingGas.AIR),
        )
        assertEquals(40 * 60, multi.bottomTimeS)
        assertTrue(multi.ttsS > single.ttsS)
        assertEquals(40000, multi.segments.maxOf { it.endDepthMm })
    }

    @Test
    fun levelShorterThanItsDescentIsAnError() {
        val plan = DecoPlanner.plan(DivePlan(listOf(PlanLevel(60000, 60)), BreathingGas.AIR))
        assertEquals(PlanError.LEVEL_TOO_SHORT, plan.error)
    }

    @Test
    fun gasUseFollowsSacAndDepth() {
        // 10 min at 10 m in salt water, 20 l/min: about 2 atm x 20 l x 9.5 min plus descent and ascent.
        val plan = DecoPlanner.plan(DivePlan(listOf(PlanLevel(10000, 10 * 60)), BreathingGas.AIR, maxEndMm = null))
        val air = plan.gasUse.single()
        assertTrue(air.litres in 400.0..440.0, "air ${air.litres}")
        assertTrue(air.decoLitres > 0 && air.decoLitres < 30)
    }

    @Test
    fun decoGasAtItsOwnSwitchDepthDoesNotWarn() {
        // Oxygen at 6 m in sea water is 1.619 bar, above a 1.6 limit, but 6 m is where the planner switches to it.
        val plan = DecoPlanner.plan(DivePlan(listOf(PlanLevel(40000, 25 * 60)), BreathingGas.AIR, listOf(BreathingGas.OXYGEN)))
        assertTrue(plan.segments.any { it.gas == BreathingGas.OXYGEN && it.startDepthMm == 6000 })
        assertTrue(plan.warnings.none { it is PlanWarning.HighPpO2 })
    }

    @Test
    fun decoGasDeeperThanItsSwitchDepthStillWarns() {
        // EAN50 switches at 21 m; breathed at 30 m as the bottom gas it is over the limit, in bar.
        val ean50 = BreathingGas(500)
        val plan = DecoPlanner.plan(DivePlan(listOf(PlanLevel(30000, 10 * 60)), ean50, listOf(ean50)))
        val high = plan.warnings.filterIsInstance<PlanWarning.HighPpO2>().single()
        assertEquals(30000, high.depthMm)
        assertTrue(high.ppO2 > 1.9, "pO2 ${high.ppO2}")
        assertEquals(1.4, high.limit)
    }

    @Test
    fun warnsAboutPpO2AndEnd() {
        val plan = DecoPlanner.plan(
            DivePlan(listOf(PlanLevel(45000, 20 * 60)), BreathingGas(320), decoGases = emptyList()),
        )
        val high = plan.warnings.filterIsInstance<PlanWarning.HighPpO2>().single()
        assertEquals(45000, high.depthMm)
        assertTrue(high.ppO2 > 1.7)
        val end = plan.warnings.filterIsInstance<PlanWarning.HighEnd>().single()
        assertTrue(end.endMm in 44000..46000)

        val hypoxic = DecoPlanner.plan(DivePlan(listOf(PlanLevel(60000, 10 * 60)), BreathingGas(100, 700)))
        assertEquals(0, hypoxic.warnings.filterIsInstance<PlanWarning.LowPpO2>().single().depthMm)
    }

    @Test
    fun oxygenExposure() {
        val plan = DecoPlanner.plan(testMetric)
        // An hour of decompression, most of it on oxygen at 6 m.
        assertTrue(plan.cnsPercent in 50..150, "cns ${plan.cnsPercent}")
        assertTrue(plan.otu in 100..250, "otu ${plan.otu}")
        val shallow = DecoPlanner.plan(DivePlan(listOf(PlanLevel(10000, 20 * 60)), BreathingGas.AIR))
        assertEquals(0, shallow.otu)
    }

    @Test
    fun switchDepthsRoundToStops() {
        val sea = WaterColumn(1013, Water.SALT)
        assertEquals(33000, sea.switchDepthMm(BreathingGas(360), 1600, 3000))
        assertEquals(21000, sea.switchDepthMm(BreathingGas(500), 1600, 3000))
        assertEquals(6000, sea.switchDepthMm(BreathingGas.OXYGEN, 1600, 3000)) // 5.8 m rounds up
        assertEquals(63000, sea.switchDepthMm(BreathingGas(210, 350), 1600, 3000))
        assertEquals(ft(110), sea.switchDepthMm(BreathingGas(360), 1600, ft(10)))
        assertEquals(33000, sea.switchDepthMm(BreathingGas(320), 1400, 3000)) // MOD 33.27 m
    }

    @Test
    fun waterColumnPressures() {
        val sea = WaterColumn(1013, Water.SALT)
        assertEquals(1013, sea.mbar(0))
        assertEquals(1013 + 1010, sea.mbar(10000)) // 10 m x 1.03 kg/l x 9.81
        assertEquals(1013 + 981, WaterColumn(1013, Water.FRESH).mbar(10000))
        assertTrue(abs(10000 - sea.depthMm(sea.mbar(10000))) < 10) // whole mbar is about 1 cm
    }

    @Test
    fun stopLevels() {
        assertEquals(listOf(0, 3000, 6000, 9000), DecoPlanner.stopLevels(imperial = false, lastStopDeep = false).take(4))
        assertEquals(listOf(0, 6000, 9000), DecoPlanner.stopLevels(imperial = false, lastStopDeep = true).take(3))
        assertEquals(listOf(0, 3048, 6096), DecoPlanner.stopLevels(imperial = true, lastStopDeep = false).take(3))
        assertEquals(380000, DecoPlanner.stopLevels(imperial = false, lastStopDeep = false).last())
    }
}
