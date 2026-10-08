package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.ATM_BAR
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A gas available to fill from. */
data class SourceGas(val name: String, val gas: BreathingGas)

/** The cylinder before blending: water volume, gauge pressure and what it holds. */
data class Cylinder(val volumeLitres: Double, val pressureBar: Double, val gas: BreathingGas)

/** A gas at a gauge pressure. */
data class Fill(val gas: BreathingGas, val pressureBar: Double)

sealed interface BlendStep {
    val fromBar: Double
    val toBar: Double
    val gasBefore: BreathingGas
    val gasAfter: BreathingGas

    /** Bleed the cylinder down to [toBar]; 0 empties it. */
    data class Drain(
        override val fromBar: Double,
        override val toBar: Double,
        override val gasBefore: BreathingGas,
        override val gasAfter: BreathingGas,
    ) : BlendStep

    /**
     * Fill [addedBar] of [source]. [topUp] marks the fill that brings the cylinder to the
     * target pressure. [litres] is the free gas at 1 atm taken from the source.
     */
    data class Add(
        val source: SourceGas,
        val topUp: Boolean,
        override val fromBar: Double,
        override val toBar: Double,
        val addedBar: Double,
        val litres: Double,
        override val gasBefore: BreathingGas,
        override val gasAfter: BreathingGas,
    ) : BlendStep
}

/**
 * A blending procedure towards [target]. [result] is what the steps produce; when not [ok] the
 * steps are still the closest the gases allow. [litresUsed] is free gas at 1 atm per source gas
 * name, in fill order.
 */
data class BlendPlan(
    val target: Fill,
    val steps: List<BlendStep>,
    val result: Fill,
    val litresUsed: Map<String, Double>,
    val ok: Boolean,
)

/** Within this the result counts as on target: O2 and He permille (0.5 percentage points), and bar. */
const val TOLERANCE_PERMILLE = 5
const val TOLERANCE_BAR = 1.0

/**
 * Partial-pressure blend from [start] to [target] using [sources]: drain if any component is
 * in excess, add helium (pure or a helium-rich mix), then top up with O2 and/or a nitrox
 * (19-40 % O2). Pressures are gauge bar; amounts follow the real-gas [Compressibility] at
 * absolute pressure, and the 1 atm left in an "empty" cylinder counts as part of the mix.
 */
fun planBlend(start: Cylinder, target: Fill, sources: List<SourceGas>): BlendPlan = Blender(start, target, sources).plan()

/** Smallest fill worth a step, bar. */
private const val MIN_ADD_BAR = 0.1

/** Smallest amount difference that counts, ideal bar. */
private const val MIN_DELTA = 0.5

/** Guard for near-singular denominators. */
private const val NEAR_ZERO = 0.0001

/** O2, He and N2 as fractions of a mix, or as amounts in ideal bar. */
private class Parts(val o2: Double, val he: Double, val n2: Double) {
    val total: Double get() = o2 + he + n2

    fun fractions(): Parts {
        val t = total
        return if (t <= NEAR_ZERO) Parts(0.0, 0.0, 0.0) else Parts(o2 / t, he / t, max(0.0, n2 / t))
    }

    fun toGas(): BreathingGas = BreathingGas.ofFractions(o2, he)

    companion object {
        fun of(gas: BreathingGas) = Parts(gas.o2 / 1000.0, gas.he / 1000.0, gas.n2 / 1000.0)
    }
}

/** Amounts of the three [gases] that together hold [want] (Cramer's rule); null if they are dependent. */
private fun solveFills(gases: List<Parts>, want: Parts): DoubleArray? {
    fun det(a: Parts, b: Parts, c: Parts) =
        a.o2 * (b.he * c.n2 - b.n2 * c.he) - b.o2 * (a.he * c.n2 - a.n2 * c.he) + c.o2 * (a.he * b.n2 - a.n2 * b.he)
    val (g0, g1, g2) = gases
    val d = det(g0, g1, g2)
    if (abs(d) < NEAR_ZERO) return null
    return doubleArrayOf(det(want, g1, g2) / d, det(g0, want, g2) / d, det(g0, g1, want) / d)
}

/** Amounts are tracked in ideal bar (see [Compressibility.idealBar]), so proportional to moles. */
private class Blender(private val start: Cylinder, private val target: Fill, sources: List<SourceGas>) {
    private val steps = mutableListOf<BlendStep>()
    private val used = LinkedHashMap<String, Double>()

    private val targetParts = Parts.of(target.gas)
    private val goal = idealBar(target.pressureBar, targetParts)
    private val goalO2 = targetParts.o2 * goal
    private val goalHe = targetParts.he * goal
    private val goalN2 = targetParts.n2 * goal

    private var bar = start.pressureBar
    private var o2: Double
    private var he: Double
    private var n2: Double

    init {
        val startParts = Parts.of(start.gas)
        val amount = idealBar(bar, startParts)
        o2 = startParts.o2 * amount
        he = startParts.he * amount
        n2 = startParts.n2 * amount
    }

    private val pureHe = sources.firstOrNull { it.gas.he > 950 && it.gas.o2 < 50 }
    private val pureO2 = sources.firstOrNull { it.gas.o2 > 950 && it.gas.he < 50 }
    private val topUps = sources.filter { it.gas.he < 50 && it.gas.o2 in 190..400 }.sortedBy { it.gas.o2 }
    private val heliumMixes = sources.filter { it.gas.he > 300 }.sortedByDescending { it.gas.he }
    private val heSource = pureHe ?: heliumMixes.firstOrNull()

    private val total get() = o2 + he + n2

    private fun fractions(): Parts = Parts(o2, he, n2).fractions()

    /** Gas in the cylinder at [gaugeBar] of a mix with [fractions], ideal bar. */
    private fun idealBar(gaugeBar: Double, fractions: Parts) = Compressibility.idealBar(gaugeBar, fractions.o2, fractions.he)

    private fun gaugeBar(idealBar: Double, fractions: Parts) = Compressibility.gaugeBar(idealBar, fractions.o2, fractions.he)

    /** The cylinder after adding [amount] ideal bar of [gas]. */
    private fun plus(gas: Parts, amount: Double) = Parts(o2 + gas.o2 * amount, he + gas.he * amount, n2 + gas.n2 * amount)

    fun plan(): BlendPlan {
        drainIfNeeded()
        addHelium()
        topUp()
        val result = Fill(fractions().toGas(), roundHalfUp(bar, 1))
        val ok = abs(result.gas.o2 - target.gas.o2) <= TOLERANCE_PERMILLE &&
            abs(result.gas.he - target.gas.he) <= TOLERANCE_PERMILLE &&
            abs(result.pressureBar - target.pressureBar) <= TOLERANCE_BAR
        return BlendPlan(target, steps.toList(), result, used.toMap(), ok)
    }

    private fun drainIfNeeded() {
        val now = fractions()
        val totalNow = total
        val missingHe = goalHe - he
        val missingO2 = goalO2 - o2
        val missingN2 = goalN2 - n2
        var keep = totalNow
        var drain = false

        // Any component already above its goal caps how much can stay in the cylinder.
        if (missingHe < -MIN_DELTA || missingN2 < -MIN_DELTA || missingO2 < -MIN_DELTA) {
            drain = true
            if (missingHe < -MIN_DELTA && now.he > 0.001) keep = min(keep, goalHe / now.he)
            if (missingO2 < -MIN_DELTA && now.o2 > 0.001) keep = min(keep, goalO2 / now.o2)
            if (missingN2 < -MIN_DELTA && now.n2 > 0.001) keep = min(keep, goalN2 / now.n2)
        }

        // Adding helium dilutes O2 and N2 alike, which the top-up may not be able to correct.
        // Solve for the amount to keep so drain + He + top-up lands on the goal.
        val heGas = heSource
        val topUpGas = topUps.firstOrNull()
        if (missingHe > MIN_DELTA && heGas != null && topUpGas != null) {
            val solved = keepBeforeHelium(now, Parts.of(heGas.gas), Parts.of(topUpGas.gas))
            if (solved == null || !solved.isFinite() || solved <= MIN_DELTA) {
                // No amount kept works (or next to nothing): start from an empty cylinder.
                drain = true
                keep = 0.0
            } else if (solved < totalNow - MIN_DELTA || bar >= target.pressureBar - MIN_DELTA) {
                drain = true
                keep = min(keep, solved)
            }
        }

        if (!drain) return
        // An empty cylinder still holds 1 atm, so the gauge cannot go below 0.
        val toBar = roundHalfUp(max(0.0, gaugeBar(keep, now)), 1)
        if (bar - toBar > MIN_DELTA) drainTo(if (toBar > MIN_DELTA) toBar else 0.0)
    }

    /**
     * Ideal bar to keep so that adding [heGas] and then topping up hits all three goals. With
     * pure O2 on hand the top-up is two gases (O2 + [topUpGas]); otherwise only [topUpGas], and
     * the amount is the unique solution. Null when nothing works.
     */
    private fun keepBeforeHelium(now: Parts, heGas: Parts, topUpGas: Parts): Double? {
        val topO2 = topUpGas.o2
        val o2Source = pureO2

        fun pureHeTopUpOnly(): Double? {
            val den = now.o2 - (1 - now.he) * topO2
            if (abs(den) < NEAR_ZERO) return null
            return (goalO2 - goal * topO2 + goalHe * topO2) / den
        }

        return when {
            o2Source != null -> mostToKeep(now, heGas, Parts.of(o2Source.gas), topUpGas)
            pureHe != null -> pureHeTopUpOnly()
            else -> {
                val coeff = now.o2 - (now.he * heGas.o2) / heGas.he - (1 - now.he / heGas.he) * topO2
                val rhs = goalO2 - (goalHe * heGas.o2) / heGas.he - (goal - goalHe / heGas.he) * topO2
                if (abs(coeff) < NEAR_ZERO) null else rhs / coeff
            }
        }
    }

    /**
     * With O2 as well there are three fills (helium source, O2, top-up gas) for three component
     * goals plus the amount kept, k: one degree of freedom. Each fill is then linear in k, and
     * none can be negative, which bounds k. Keep the most the bounds allow, so the cylinder is
     * only drained when a component would otherwise overshoot. Null when no k works.
     */
    private fun mostToKeep(now: Parts, heGas: Parts, oxygen: Parts, topUpGas: Parts): Double? {
        val fills = listOf(heGas, oxygen, topUpGas)
        // fill_i = fromEmpty_i - k * perKept_i
        val fromEmpty = solveFills(fills, Parts(goalO2, goalHe, goalN2)) ?: return null
        val perKept = solveFills(fills, now) ?: return null
        var upper = total
        var lower = 0.0
        for (i in fills.indices) {
            val a = fromEmpty[i]
            val b = perKept[i]
            when {
                b > NEAR_ZERO -> upper = min(upper, a / b)
                b < -NEAR_ZERO -> lower = max(lower, a / b)
                a < -NEAR_ZERO -> return null
            }
        }
        return if (lower <= upper + NEAR_ZERO) upper else null
    }

    private fun drainTo(toBar: Double) {
        if (bar <= toBar) return
        val from = bar
        val before = fractions()
        // The mix is unchanged; the amount follows pressure over Z.
        val ratio = idealBar(toBar, before) / idealBar(from, before)
        o2 *= ratio
        he *= ratio
        n2 *= ratio
        bar = toBar
        steps += BlendStep.Drain(roundHalfUp(from, 2), roundHalfUp(toBar, 2), before.toGas(), fractions().toGas())
    }

    private fun addHelium() {
        val missing = goalHe - he
        if (missing <= MIN_ADD_BAR) return
        val source = heSource ?: return
        val gas = Parts.of(source.gas)
        val after = plus(gas, missing / gas.he)
        addGas(source, gaugeBar(after.total, after.fractions()) - bar, topUp = false)
    }

    private fun topUp() {
        val remaining = target.pressureBar - bar
        if (remaining <= MIN_ADD_BAR) return
        val o2Source = pureO2
        if (topUps.isEmpty()) {
            if (o2Source != null) addGas(o2Source, remaining, topUp = false)
            return
        }

        // The single top-up gas that lands closest to the target mix.
        var best = topUps.first()
        var bestMiss = Double.POSITIVE_INFINITY
        for (source in topUps) {
            val gas = Parts.of(source.gas)
            val after = plus(gas, amountToReach(gas, target.pressureBar)).fractions()
            val miss = abs(after.o2 - targetParts.o2) + abs(after.he - targetParts.he)
            if (miss < bestMiss) {
                bestMiss = miss
                best = source
            }
        }
        // Not close enough on its own (0.7 points): take the leanest so O2 can make up the difference.
        if (o2Source != null && bestMiss > 0.007) best = topUps.first()

        if (o2Source != null && abs(best.gas.o2 - o2Source.gas.o2) > 100) {
            // O2 first, then the top-up gas to the target pressure takes the rounding.
            val o2Needed = o2FillTo(Parts.of(o2Source.gas), Parts.of(best.gas))?.let { it - bar } ?: 0.0
            var o2Bar = max(0.0, min(remaining, roundHalfUp(max(0.0, o2Needed), 1)))
            var restBar = max(0.0, roundHalfUp(remaining - o2Bar, 1))
            // A top-up too small to make leaves the cylinder short; O2 takes it instead.
            if (o2Bar > MIN_ADD_BAR && restBar <= MIN_ADD_BAR) {
                o2Bar = roundHalfUp(remaining, 1)
                restBar = 0.0
            }
            if (o2Bar > MIN_ADD_BAR) addGas(o2Source, o2Bar, topUp = false)
            if (restBar > MIN_ADD_BAR) addGas(best, restBar, topUp = true)
        } else {
            addGas(best, target.pressureBar - bar, topUp = true)
        }
    }

    /**
     * Gauge pressure to fill [oxygen] to so that topping up with [topUpGas] to the target
     * pressure lands on the target O2 fraction. The final amount depends on the final mix's Z,
     * so iterate. Null when the two gases hold the same O2.
     */
    private fun o2FillTo(oxygen: Parts, topUpGas: Parts): Double? {
        if (abs(oxygen.o2 - topUpGas.o2) < NEAR_ZERO) return null
        val now = total
        var final = goal
        var o2Amount = 0.0
        repeat(8) {
            // O2 balance: o2 + oxygen.o2 * o2Amount + topUpGas.o2 * (final - now - o2Amount) = target * final
            o2Amount = (targetParts.o2 * final - o2 - topUpGas.o2 * (final - now)) / (oxygen.o2 - topUpGas.o2)
            val heFinal = (he + oxygen.he * o2Amount + topUpGas.he * (final - now - o2Amount)) / final
            final = Compressibility.idealBar(target.pressureBar, targetParts.o2, heFinal)
        }
        val after = plus(oxygen, max(0.0, o2Amount))
        return gaugeBar(after.total, after.fractions())
    }

    /**
     * Ideal bar of [gas] that brings the cylinder to [toBar]. The gauge follows the Z of the
     * resulting mix, so iterate on the mix.
     */
    private fun amountToReach(gas: Parts, toBar: Double): Double {
        val now = total
        var amount = toBar - bar
        repeat(20) {
            val next = idealBar(toBar, plus(gas, amount).fractions()) - now
            if (abs(next - amount) < 1e-7) return next
            amount = next
        }
        return amount
    }

    private fun addGas(source: SourceGas, amountBar: Double, topUp: Boolean) {
        val added = roundHalfUp(amountBar, 1)
        if (added <= MIN_ADD_BAR) return
        val from = bar
        val before = fractions()
        val toBar = bar + added
        val gas = Parts.of(source.gas)
        val amount = amountToReach(gas, toBar)
        o2 += gas.o2 * amount
        he += gas.he * amount
        n2 += gas.n2 * amount
        bar = toBar
        val litres = roundHalfUp(amount * start.volumeLitres / ATM_BAR, 1)
        used[source.name] = (used[source.name] ?: 0.0) + litres
        steps += BlendStep.Add(source, topUp, roundHalfUp(from, 2), roundHalfUp(bar, 2), added, litres, before.toGas(), fractions().toGas())
    }
}
