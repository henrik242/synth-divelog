package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenarios from Subsurface's planner tests (tests/testplan.cpp). Subsurface accepts 1 % of the
 * runtime plus 60 s against its benchmarks; ours match its known runtimes to the second.
 */
class DecoPlannerTest {
    private fun ft(v: Int) = DecoPlanner.ftToMm(v)

    // testMetric: 79 m for 30 min on 15/45 incl. a 23 m/min descent, EAN36 and O2 at pO2 1.6,
    // GF 100/100, ascent 30 ft/min and 10 ft/min the last 6 m, last stop 6 m.
    private val testMetric = DivePlan(
        levels = listOf(PlanLevel(79000, 30 * 60)),
        bottomGas = BreathingGas(150, 450),
        decoGases = listOf(BreathingGas(360), BreathingGas.OXYGEN),
        gf = GradientFactors(1.0, 1.0),
        descentRate = 23000,
        ascentRates = AscentRates(ft(30), ft(30), ft(30), ft(10)),
        lastStopDeep = true,
    )

    // testImperial: the same in feet, 260 ft, 75 ft/min descent, 10 ft stops.
    private val testImperial = testMetric.copy(
        levels = listOf(PlanLevel(ft(260), 30 * 60)),
        descentRate = ft(75),
        imperialStops = true,
    )

    private fun assertRuntime(expectedS: Int, plan: DecoPlan) {
        val allowed = expectedS / 100 + 60
        assertTrue(abs(plan.runtimeS - expectedS) <= allowed, "runtime ${plan.runtimeS}, expected $expectedS")
    }

    /** Gas and depth of each switch along the ascent. */
    private fun switches(plan: DecoPlan): List<Pair<String, Int>> =
        plan.segments.zipWithNext().filter { (a, b) -> a.gas != b.gas }.map { (_, b) -> b.gas.name to b.startDepthMm }

    @Test
    fun subsurfaceMetric() {
        val plan = DecoPlanner.plan(testMetric)
        assertNull(plan.error)
        assertRuntime(109 * 60, plan)
        assertEquals(109 * 60, plan.runtimeS) // Subsurface's own result
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
