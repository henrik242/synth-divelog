package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A gas available to fill from. [o2] and [he] are percent. */
data class SourceGas(val name: String, val o2: Double, val he: Double)

/** The cylinder before blending: water volume, gauge pressure and O2/He percent. */
data class Cylinder(val volumeLitres: Double, val pressureBar: Double, val o2: Double, val he: Double)

/** A mix (O2/He percent) at a gauge pressure. */
data class Fill(val o2: Double, val he: Double, val pressureBar: Double)

/** O2/He percent, rounded to 0.1 for display. */
data class Mix(val o2: Double, val he: Double)

sealed interface BlendStep {
    val fromBar: Double
    val toBar: Double
    val mixBefore: Mix
    val mixAfter: Mix

    /** Bleed the cylinder down to [toBar]; 0 empties it. */
    data class Drain(
        override val fromBar: Double,
        override val toBar: Double,
        override val mixBefore: Mix,
        override val mixAfter: Mix,
    ) : BlendStep

    /**
     * Fill [addedBar] of [gas]. [topUp] marks the fill that brings the cylinder to the
     * target pressure. [litres] is the free gas taken from the source.
     */
    data class Add(
        val gas: SourceGas,
        val topUp: Boolean,
        override val fromBar: Double,
        override val toBar: Double,
        val addedBar: Double,
        val litres: Double,
        override val mixBefore: Mix,
        override val mixAfter: Mix,
    ) : BlendStep
}

enum class BlendProblem {
    /** The target O2 and He add up to more than 100 %. */
    MIX_OVER_100,

    /** The available gases cannot reach the target within tolerance. */
    OFF_TARGET,
}

/**
 * A blending procedure towards [target]. [result] is what the steps produce; when [problem] is
 * [BlendProblem.OFF_TARGET] the steps are still the closest the gases allow.
 * [litresUsed] is free gas per source gas name, in fill order.
 */
data class BlendPlan(
    val target: Fill,
    val steps: List<BlendStep>,
    val result: Fill,
    val litresUsed: Map<String, Double>,
    val problem: BlendProblem?,
) {
    val ok: Boolean get() = problem == null
}

/** Within this the result counts as on target: O2 and He percent points, and bar. */
const val TOLERANCE_PERCENT = 0.5
const val TOLERANCE_BAR = 1.0

/**
 * Partial-pressure blend from [start] to [target] using [gases]: drain if any component
 * is in excess, add helium (pure or a helium-rich mix), then top up with O2 and/or a
 * nitrox (19-40 % O2). Accounts for real-gas compressibility; all pressures are gauge bar.
 */
fun planBlend(start: Cylinder, target: Fill, gases: List<SourceGas>): BlendPlan {
    if (target.o2 + target.he > 100) {
        return BlendPlan(target, emptyList(), Fill(start.o2, start.he, start.pressureBar), emptyMap(), BlendProblem.MIX_OVER_100)
    }
    return Blender(start, target, gases).plan()
}

/** Smallest fill worth a step, bar. */
private const val MIN_ADD_BAR = 0.1

/** Smallest amount difference that counts, mole bar. */
private const val MIN_DELTA = 0.5

/** Guard for near-singular denominators. */
private const val NEAR_ZERO = 0.0001

/** JS-style half-up rounding, so plans match the web blender exactly. */
private fun roundTo(value: Double, decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return floor(value * factor + 0.5) / factor
}

private class Fractions(val o2: Double, val he: Double, val n2: Double) {
    fun toMix() = Mix(roundTo(o2 * 100, 1), roundTo(he * 100, 1))
}

/**
 * Amounts are tracked in "mole bar": the gauge pressure each component would show as
 * an ideal gas, so proportional to moles. Gauge bar = Z * mole bar.
 */
private class Blender(private val start: Cylinder, private val target: Fill, gases: List<SourceGas>) {
    private val steps = mutableListOf<BlendStep>()
    private val used = LinkedHashMap<String, Double>()

    private val targetO2Frac = target.o2 / 100
    private val targetHeFrac = target.he / 100
    private val goal = target.pressureBar / Compressibility.z(targetO2Frac, targetHeFrac, target.pressureBar)
    private val goalO2 = targetO2Frac * goal
    private val goalHe = targetHeFrac * goal
    private val goalN2 = (1 - targetO2Frac - targetHeFrac) * goal

    private var bar = start.pressureBar
    private var o2: Double
    private var he: Double
    private var n2: Double

    init {
        val startO2 = start.o2 / 100
        val startHe = start.he / 100
        val amount = if (bar <= 0) 0.0 else bar / Compressibility.z(startO2, startHe, bar)
        o2 = startO2 * amount
        he = startHe * amount
        n2 = max(0.0, (1 - startO2 - startHe) * amount)
    }

    private val pureHe = gases.firstOrNull { it.he > 95 && it.o2 < 5 }
    private val pureO2 = gases.firstOrNull { it.o2 > 95 && it.he < 5 }
    private val topUps = gases.filter { it.he < 5 && it.o2 >= 19 && it.o2 <= 40 }.sortedBy { it.o2 }
    private val heliumMixes = gases.filter { it.he > 30 }.sortedByDescending { it.he }
    private val heSource = pureHe ?: heliumMixes.firstOrNull()

    private val total get() = o2 + he + n2

    private fun fractions(): Fractions {
        val t = total
        if (t <= NEAR_ZERO) return Fractions(0.0, 0.0, 0.0)
        return Fractions(o2 / t, he / t, max(0.0, n2 / t))
    }

    fun plan(): BlendPlan {
        drainIfNeeded()
        addHelium()
        topUp()
        val result = fractions().toMix().let { Fill(it.o2, it.he, roundTo(bar, 1)) }
        val off = abs(result.o2 - target.o2) > TOLERANCE_PERCENT ||
            abs(result.he - target.he) > TOLERANCE_PERCENT ||
            abs(result.pressureBar - target.pressureBar) > TOLERANCE_BAR
        return BlendPlan(target, steps.toList(), result, used.toMap(), if (off) BlendProblem.OFF_TARGET else null)
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
            val solved = keepBeforeHelium(now, heGas, topUpGas)
            if (solved == null || !solved.isFinite()) {
                // Degenerate (e.g. start O2 equals top-up O2): only an empty cylinder works.
                drain = true
                keep = 0.0
            } else if (solved <= MIN_DELTA && pureHe == null && pureO2 == null) {
                drain = true
                keep = 0.0
            } else if (solved > MIN_DELTA &&
                (solved < totalNow - MIN_DELTA || bar >= target.pressureBar - MIN_DELTA)
            ) {
                drain = true
                keep = min(keep, solved)
            }
        }

        if (!drain) return
        val toBar = roundTo(if (keep <= 0) 0.0 else Compressibility.gaugeBar(keep, now.o2, now.he), 1)
        val drained = bar - toBar
        if (drained > MIN_DELTA) drainTo(if (toBar > MIN_DELTA) toBar else 0.0)
    }

    /**
     * Mole bar to keep so that adding [heGas] and then topping up hits all three goals.
     * With pure O2 on hand the top-up is two gases (O2 + [topUpGas]); otherwise only
     * [topUpGas]. Null when the system is singular.
     */
    private fun keepBeforeHelium(now: Fractions, heGas: SourceGas, topUpGas: SourceGas): Double? {
        val topO2 = topUpGas.o2 / 100
        val topN2 = (100 - topUpGas.o2 - topUpGas.he) / 100
        val heO2 = heGas.o2 / 100
        val heHe = heGas.he / 100
        val heN2 = (100 - heGas.o2 - heGas.he) / 100

        fun pureHeTopUpOnly(): Double? {
            val den = now.o2 - (1 - now.he) * topO2
            if (abs(den) < NEAR_ZERO) return null
            return (goalO2 - goal * topO2 + goalHe * topO2) / den
        }

        return when {
            pureO2 != null && pureHe != null -> {
                val coeff = now.o2 - 1 + now.he - (now.n2 * (topO2 - 1)) / topN2
                val rhs = goalO2 - goal + goalHe - (goalN2 * (topO2 - 1)) / topN2
                if (abs(coeff) > NEAR_ZERO) rhs / coeff else pureHeTopUpOnly()
            }
            // Helium mix plus O2: the N2 balance alone fixes the amount kept.
            pureO2 != null -> {
                val coeff = now.n2 - (now.he * heN2) / heHe
                val rhs = goalN2 - (goalHe * heN2) / heHe
                if (abs(coeff) < NEAR_ZERO) null else rhs / coeff
            }
            pureHe != null -> pureHeTopUpOnly()
            else -> {
                val coeff = now.o2 - (now.he * heO2) / heHe - (1 - now.he / heHe) * topO2
                val rhs = goalO2 - (goalHe * heO2) / heHe - (goal - goalHe / heHe) * topO2
                if (abs(coeff) < NEAR_ZERO) null else rhs / coeff
            }
        }
    }

    private fun drainTo(toBar: Double) {
        if (bar <= toBar) return
        val from = bar
        val before = fractions()
        // Composition is unchanged; the amount scales with gauge / Z.
        val zBefore = Compressibility.z(before.o2, before.he, from)
        val zAfter = if (toBar <= 0) 1.0 else Compressibility.z(before.o2, before.he, toBar)
        val ratio = if (from <= 0) 0.0 else toBar / zAfter / (from / zBefore)
        o2 *= ratio
        he *= ratio
        n2 *= ratio
        bar = toBar
        steps += BlendStep.Drain(roundTo(from, 2), roundTo(toBar, 2), before.toMix(), fractions().toMix())
    }

    private fun addHelium() {
        val missing = goalHe - he
        if (missing <= MIN_ADD_BAR) return
        val gas = heSource ?: return
        val heFrac = gas.he / 100
        // Gauge pressure after the fill, from the Z of the resulting mix.
        val add = missing / heFrac
        val after = total + add
        val mixO2 = (o2 + gas.o2 / 100 * add) / after
        val mixHe = (he + heFrac * add) / after
        addGas(gas, Compressibility.gaugeBar(after, mixO2, mixHe) - bar, topUp = false)
    }

    private fun topUp() {
        val remaining = target.pressureBar - bar
        if (remaining <= MIN_ADD_BAR) return
        val o2Gas = pureO2
        if (topUps.isEmpty()) {
            if (o2Gas != null) addGas(o2Gas, remaining, topUp = false)
            return
        }

        // The single top-up gas that lands closest to the target mix.
        var best = topUps.first()
        var bestMiss = Double.POSITIVE_INFINITY
        for (gas in topUps) {
            val added = remaining / Compressibility.z(gas.o2 / 100, gas.he / 100, target.pressureBar)
            val after = total + added
            val mixO2 = (o2 + gas.o2 / 100 * added) / after
            val mixHe = (he + gas.he / 100 * added) / after
            val miss = abs(mixO2 * 100 - target.o2) + abs(mixHe * 100 - target.he)
            if (miss < bestMiss) {
                bestMiss = miss
                best = gas
            }
        }
        // Not close enough on its own: take the leanest so O2 can make up the difference.
        if (o2Gas != null && bestMiss > 0.7) best = topUps.first()

        if (o2Gas != null && abs(best.o2 - o2Gas.o2) > 10) {
            // O2 first, then the top-up gas to the target pressure takes the rounding.
            val o2Bar = max(0.0, min(remaining, roundTo(max(0.0, o2Before(remaining, o2Gas, best)), 1)))
            val restBar = max(0.0, roundTo(remaining - o2Bar, 1))
            if (o2Bar > MIN_ADD_BAR) addGas(o2Gas, o2Bar, topUp = false)
            if (restBar > MIN_ADD_BAR) addGas(best, restBar, topUp = true)
        } else {
            addGas(best, target.pressureBar - bar, topUp = true)
        }
    }

    /**
     * Gauge bar of [o2Gas] to add so that filling the remaining [remaining] bar with
     * [topUpGas] hits the target O2 fraction. The top-up ends at the known target pressure;
     * the O2 Z depends on the answer, so iterate. May be negative.
     */
    private fun o2Before(remaining: Double, o2Gas: SourceGas, topUpGas: SourceGas): Double {
        val zTop = Compressibility.z(topUpGas.o2 / 100, topUpGas.he / 100, bar + remaining)
        val q = topUpGas.o2 / 100
        val f = targetO2Frac
        val amountNow = total
        var zO2 = Compressibility.z(o2Gas.o2 / 100, o2Gas.he / 100, bar + remaining)
        var o2Bar = 0.0
        repeat(3) {
            val den = (1 - f) / zO2 - (q - f) / zTop
            if (abs(den) < NEAR_ZERO) return o2Bar
            val candidate = (f * amountNow - o2 - (remaining * (q - f)) / zTop) / den
            zO2 = Compressibility.z(o2Gas.o2 / 100, o2Gas.he / 100, bar + max(0.0, candidate))
            o2Bar = candidate
        }
        return o2Bar
    }

    private fun addGas(gas: SourceGas, amountBar: Double, topUp: Boolean) {
        val added = roundTo(amountBar, 1)
        if (added <= MIN_ADD_BAR) return
        val from = bar
        val before = fractions()
        val toBar = bar + added
        // Z at the end pressure of the fill.
        val amount = added / Compressibility.z(gas.o2 / 100, gas.he / 100, toBar)
        o2 += gas.o2 / 100 * amount
        he += gas.he / 100 * amount
        n2 = max(0.0, n2 + max(0.0, (100 - gas.o2 - gas.he) / 100) * amount)
        bar = toBar
        val litres = roundTo(amount * start.volumeLitres, 1)
        used[gas.name] = (used[gas.name] ?: 0.0) + litres
        steps += BlendStep.Add(gas, topUp, roundTo(from, 2), roundTo(bar, 2), added, litres, before.toMix(), fractions().toMix())
    }
}
