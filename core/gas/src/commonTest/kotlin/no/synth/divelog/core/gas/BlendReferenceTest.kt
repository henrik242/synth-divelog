package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the blender's steps with an independent model: Subsurface's Z fit written out here in
 * its own permille form (core/gas-model.cpp), and each step replayed as an isothermal fill to
 * the step's gauge pressure. The final mix must be within 0.5 points of the target.
 */
class BlendReferenceTest {
    private fun z(o2Permille: Double, hePermille: Double, bar: Double): Double {
        val p = bar.coerceIn(0.0, 500.0)
        fun virialMinusOne(c0: Double, c1: Double, c2: Double) = p * c0 + p * p * c1 + p * p * p * c2
        val sum = virialMinusOne(-7.18092073703e-04, 2.81852572808e-06, -1.50290620492e-09) * o2Permille +
            virialMinusOne(4.87320026468e-04, -8.83632921053e-08, 5.33304543646e-11) * hePermille +
            virialMinusOne(-2.19260353292e-04, 2.92844845532e-06, -2.07613482075e-09) * (1000 - o2Permille - hePermille)
        return sum * 0.001 + 1.0
    }

    /** Moles, as ideal bar, at [gauge] of a mix with O2 and He permille. */
    private fun moles(gauge: Double, o2: Double, he: Double) = (gauge + 1.01325) / z(o2, he, gauge + 1.01325)

    /** Final O2 and He percent and gauge pressure after replaying [plan]'s steps from [start]. */
    private fun replay(start: Cylinder, plan: BlendPlan): Triple<Double, Double, Double> {
        var bar = start.pressureBar
        var total = moles(bar, start.gas.o2.toDouble(), start.gas.he.toDouble())
        var o2 = start.gas.o2 * total
        var he = start.gas.he * total
        for (step in plan.steps) {
            when (step) {
                is BlendStep.Drain -> {
                    val ratio = moles(step.toBar, o2 / total, he / total) / moles(bar, o2 / total, he / total)
                    o2 *= ratio
                    he *= ratio
                    total *= ratio
                }
                is BlendStep.Add -> {
                    val gas = step.source.gas
                    var x = step.toBar - bar
                    repeat(100) {
                        x = moles(step.toBar, (o2 + gas.o2 * x) / (total + x), (he + gas.he * x) / (total + x)) - total
                    }
                    o2 += gas.o2 * x
                    he += gas.he * x
                    total += x
                }
            }
            bar = step.toBar
        }
        return Triple(o2 / total / 10, he / total / 10, bar)
    }

    private val air = SourceGas("Air", BreathingGas.AIR)
    private val oxygen = SourceGas("O2", BreathingGas.OXYGEN)
    private val helium = SourceGas("Helium", BreathingGas.HELIUM)
    private val empty = Cylinder(12.0, 0.0, BreathingGas.AIR)

    private fun check(start: Cylinder, target: Fill, sources: List<SourceGas>) {
        val plan = planBlend(start, target, sources)
        assertTrue(plan.ok, "$target: ${plan.result}")
        val (o2, he, bar) = replay(start, plan)
        val what = "$target from $start: replayed $o2/$he at $bar"
        assertTrue(abs(o2 - target.gas.o2 / 10.0) <= 0.5, what)
        assertTrue(abs(he - target.gas.he / 10.0) <= 0.5, what)
        assertEquals(target.pressureBar, bar, what)
        // The blender's own prediction agrees with the replay.
        assertTrue(abs(o2 - plan.result.gas.o2 / 10.0) <= 0.1 && abs(he - plan.result.gas.he / 10.0) <= 0.1, what)
    }

    @Test
    fun nitrox() {
        check(empty, Fill(BreathingGas(320), 232.0), listOf(air, oxygen))
        check(empty, Fill(BreathingGas(360), 232.0), listOf(air, oxygen))
        check(empty, Fill(BreathingGas(320), 300.0), listOf(air, oxygen))
        check(empty, Fill(BreathingGas(500), 200.0), listOf(air, oxygen))
        check(Cylinder(12.0, 50.0, BreathingGas.AIR), Fill(BreathingGas(320), 200.0), listOf(air, oxygen))
    }

    @Test
    fun trimix() {
        val sources = listOf(air, oxygen, helium)
        check(empty, Fill(BreathingGas(180, 450), 232.0), sources)
        check(empty, Fill(BreathingGas(210, 350), 232.0), sources)
        check(empty, Fill(BreathingGas(100, 700), 232.0), sources)
        check(Cylinder(12.0, 100.0, BreathingGas.AIR), Fill(BreathingGas(180, 450), 220.0), sources)
        check(Cylinder(11.0, 220.0, BreathingGas(320, 100)), Fill(BreathingGas(180, 450), 220.0), sources)
    }

    @Test
    fun idealPartialPressuresComeOutRich() {
        // The textbook ideal-gas recipe for EAN32 at 232 bar: O2 to 232 x 11 / 79 bar, then air.
        val o2Bar = 232.0 * 11 / 79
        val ideal = BlendPlan(
            Fill(BreathingGas(320), 232.0),
            listOf(
                BlendStep.Add(oxygen, false, 0.0, o2Bar, o2Bar, 0.0, BreathingGas.AIR, BreathingGas.OXYGEN),
                BlendStep.Add(air, true, o2Bar, 232.0, 232.0 - o2Bar, 0.0, BreathingGas.OXYGEN, BreathingGas(320)),
            ),
            Fill(BreathingGas(320), 232.0),
            emptyMap(),
            ok = true,
        )
        val (o2, _, _) = replay(empty, ideal)
        assertTrue(o2 in 32.6..32.8, "ideal recipe gives $o2 %")
        // The blender adds less O2 than the ideal recipe to land on 32 %.
        val real = planBlend(empty, Fill(BreathingGas(320), 232.0), listOf(air, oxygen))
        assertTrue(real.steps.first().toBar < o2Bar)
    }
}
