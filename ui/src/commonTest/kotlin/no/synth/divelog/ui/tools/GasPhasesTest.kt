package no.synth.divelog.ui.tools

import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.DecoPlanner
import no.synth.divelog.core.gas.DivePlan
import no.synth.divelog.core.gas.PlanLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GasPhasesTest {
    private val travel = BreathingGas(210, 350)
    private val bottom = BreathingGas(100, 700)

    // 10/70 is hypoxic at the surface, so the descent is on 21/35, which comes back on the ascent.
    private val plan = DecoPlanner.plan(
        DivePlan(listOf(PlanLevel(90000, 20 * 60)), bottom, listOf(BreathingGas.OXYGEN, BreathingGas(500), travel)),
    )
    private val phases = gasPhases(plan)

    @Test
    fun oneLinePerGasInTheOrderBreathed() {
        assertEquals(listOf(travel, bottom, travel, BreathingGas(500), BreathingGas.OXYGEN), phases.map { it.gas })
    }

    @Test
    fun thePhasesCoverTheDive() {
        assertEquals(0, phases.first().startS)
        assertEquals(plan.runtimeS, phases.last().endS)
        phases.zipWithNext { a, b -> assertEquals(a.endS, b.startS) }
        assertEquals(plan.runtimeS, phases.sumOf { it.durationS })
        assertEquals(0, phases.last().endMm)
    }

    @Test
    fun eachPhaseKnowsWhatItIs() {
        val (descent, onBottom, ascent) = phases
        assertTrue(descent.descent && !descent.bottom && descent.startMm == 0 && descent.endMm == descent.deepestMm)
        assertTrue(onBottom.bottom && !onBottom.descent && onBottom.deepestMm == 90000)
        assertTrue(!ascent.descent && !ascent.bottom && ascent.deepestMm == ascent.startMm)
        // A switch at a stop depth splits that stop between two gases.
        assertTrue(phases.sumOf { it.stops } >= plan.stops.map { it.endDepthMm }.distinct().size)
        assertEquals(0, descent.stops)
    }
}
