// SPDX-License-Identifier: GPL-2.0-only
// The decompression planner follows Subsurface's implementation (core/planner.cpp,
// core/plannernotes.cpp, core/divelist.cpp).
package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.ATM_BAR
import no.synth.divelog.core.model.units.feetToMm
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/** One level of the bottom profile: go to [depthMm] and leave it [durationS] later, travel included. */
data class PlanLevel(val depthMm: Int, val durationS: Int)

/**
 * Ascent rates in mm/min: [ascent] below 6 m, [lastSixMetres] from there to the surface.
 * Subsurface can also slow down by depth relative to the average depth; its reference plans
 * use one rate below 6 m, as here.
 */
data class AscentRates(val ascent: Int = 9000, val lastSixMetres: Int = 9000) {
    init {
        require(ascent >= 60 && lastSixMetres >= 60) { "Ascent rates must be at least 60 mm/min" }
    }
}

/** A decompression plan's inputs. Defaults are Subsurface's planner defaults. */
data class DivePlan(
    val levels: List<PlanLevel>,
    val bottomGas: BreathingGas,
    val decoGases: List<BreathingGas> = emptyList(),
    val gf: GradientFactors = GradientFactors(0.30, 0.75),
    val bottomPpO2Mbar: Int = 1400,
    /** Deco gases are switched to where they reach this pO2, or shallower to keep within [maxEndMm]. */
    val decoPpO2Mbar: Int = 1600,
    val descentRate: Int = 18000,
    val ascentRates: AscentRates = AscentRates(),
    /** Last stop at 6 m (20 ft) instead of 3 m (10 ft). */
    val lastStopDeep: Boolean = false,
    /** Stops at 10 ft steps rather than 3 m. */
    val imperialStops: Boolean = false,
    val water: Water = Water.SALT,
    val surfaceMbar: Int = 1013,
    val bottomSacMlPerMin: Int = 20000,
    val decoSacMlPerMin: Int = 17000,
    /** Time spent at a switch before ascending further, unless switching to oxygen. */
    val gasSwitchS: Int = 60,
    /**
     * END limit; O2 counts as narcotic. Deco gases are not switched to deeper than this allows,
     * and going over it is warned about. Null for no END limit.
     */
    val maxEndMm: Int? = 30000,
    /**
     * When the bottom gas is hypoxic at the surface, descend on the leanest other gas that is
     * within the limits until the bottom gas is breathable.
     */
    val travelGas: Boolean = true,
) {
    init {
        require(levels.isNotEmpty()) { "A plan needs at least one level" }
        require(levels.all { it.depthMm > 0 && it.durationS > 0 }) { "Levels need a depth and a duration" }
        require(bottomGas.o2 > 0 && decoGases.all { it.o2 > 0 }) { "Gases need oxygen" }
        require(descentRate >= 60) { "Descent rate must be at least 60 mm/min" }
        require(surfaceMbar in 500..1100) { "Surface pressure out of range" }
    }
}

/** A stretch of the plan between two waypoints, breathing [gas]. [bottom] for the planned profile. */
data class PlanSegment(
    val startS: Int,
    val endS: Int,
    val startDepthMm: Int,
    val endDepthMm: Int,
    val gas: BreathingGas,
    val bottom: Boolean,
) {
    val durationS: Int get() = endS - startS
    val isStop: Boolean get() = startDepthMm == endDepthMm && !bottom
}

data class GasUse(val gas: BreathingGas, val litres: Double, val decoLitres: Double)

sealed interface PlanWarning {
    val gas: BreathingGas
    val depthMm: Int

    /** pO2 above the limit (bottom or deco), bar. */
    data class HighPpO2(override val gas: BreathingGas, override val depthMm: Int, val ppO2: Double, val limit: Double) : PlanWarning

    /** pO2 below [MIN_PPO2]: hypoxic. */
    data class LowPpO2(override val gas: BreathingGas, override val depthMm: Int, val ppO2: Double) : PlanWarning

    data class HighEnd(override val gas: BreathingGas, override val depthMm: Int, val endMm: Int) : PlanWarning
}

enum class PlanError {
    /** A stop would last more than two days. */
    DECO_TIMEOUT,

    /** A level is shorter than the travel to it. */
    LEVEL_TOO_SHORT,
}

data class DecoPlan(
    val segments: List<PlanSegment>,
    val runtimeS: Int,
    /** End of the planned profile. */
    val bottomTimeS: Int,
    /** Ceiling at the end of the bottom profile, smooth, mm. */
    val bottomCeilingMm: Int,
    val firstStopMm: Int?,
    val gasUse: List<GasUse>,
    val cnsPercent: Int,
    val otu: Int,
    val warnings: List<PlanWarning>,
    val error: PlanError?,
) {
    /** Time to surface from the end of the bottom profile. */
    val ttsS: Int get() = runtimeS - bottomTimeS
    val stops: List<PlanSegment> get() = segments.filter { it.isStop }
}

private const val ASCENT_STEP_S = 2
private const val STOP_STEP_S = 60
private const val MAX_STOP_S = 48 * 3600
private const val TIMEOUT_STOP_S = 50 * 3600
private const val SIX_METRES_MM = 6000

/** Buhlmann ZHL-16C with gradient factors: plans the ascent for a [DivePlan]. */
object DecoPlanner {
    fun plan(plan: DivePlan): DecoPlan = PlanRun(plan).run()

    /** Possible stop depths, mm, ascending from the surface. */
    internal fun stopLevels(imperial: Boolean, lastStopDeep: Boolean): List<Int> {
        val levels = if (imperial) {
            ((0..300 step 10) + listOf(
                333, 367, 400, 433, 467, 500, 533, 567, 600, 633, 667,
                733, 800, 867, 933, 1000, 1067, 1133, 1200, 1267,
            )).map { feetToMm(it) }
        } else {
            ((0..90 step 3) + (100..200 step 10) + (220..380 step 20)).map { it * 1000 }
        }
        // A 6 m last stop drops the 3 m level.
        return if (lastStopDeep) levels.filterIndexed { i, _ -> i != 1 } else levels
    }
}

private class Waypoint(val timeS: Int, val depthMm: Int, val gas: BreathingGas, val bottom: Boolean)

private class GasChange(val depthMm: Int, val gas: BreathingGas)

private class PlanRun(private val plan: DivePlan) {
    private val water = WaterColumn(plan.surfaceMbar, plan.water)
    private val surfaceBar = water.surfaceBar
    private val waypoints = mutableListOf<Waypoint>()

    // mm/s, truncated as Subsurface stores them.
    private val ascentMmPerS = plan.ascentRates.ascent / 60
    private val lastSixMetresMmPerS = plan.ascentRates.lastSixMetres / 60

    private val stepMm = if (plan.imperialStops) feetToMm(10) else 3000
    // Where each deco gas is switched to; the pO2 check tolerates it from there up.
    private val switches = plan.decoGases.map { GasChange(switchDepthMm(it), it) }

    /** Where [gas] reaches the deco pO2, or the stop above where its END would pass the limit. */
    private fun switchDepthMm(gas: BreathingGas): Int {
        val byPpO2 = water.switchDepthMm(gas, plan.decoPpO2Mbar, stepMm)
        val byEnd = endLimitMm(gas) ?: return byPpO2
        return minOf(byPpO2, byEnd / stepMm * stepMm)
    }

    /** Deepest depth, mm, where [gas]'s END is within the limit; null without a limit. */
    private fun endLimitMm(gas: BreathingGas): Int? {
        val maxEnd = plan.maxEndMm ?: return null
        if (gas.he >= 1000) return null
        return water.depthMm(water.mbar(maxEnd) * 1000 / (1000 - gas.he))
    }

    /**
     * The gas to descend on until [depthMm], where the bottom gas becomes breathable: the leanest
     * other gas that is breathable at the surface and within the bottom pO2 and END limits at
     * [depthMm]. Null when none qualifies.
     */
    private fun travelGas(depthMm: Int): BreathingGas? = plan.decoGases
        .filter { gas ->
            gas.o2 / 1000.0 * water.bar(0) >= MIN_PPO2 &&
                ppO2Mbar(gas, depthMm) <= plan.bottomPpO2Mbar &&
                (endLimitMm(gas)?.let { depthMm <= it } ?: true)
        }
        .minWithOrNull(compareBy<BreathingGas> { it.o2 }.thenByDescending { it.he })

    /** First stop-grid depth, mm, where the bottom gas is no longer hypoxic. */
    private fun bottomGasBreathableMm(): Int {
        val minMbar = MIN_PPO2 * 1_000_000 / plan.bottomGas.o2
        val depth = water.depthMm(ceil(minMbar).toInt())
        return (depth + stepMm - 1) / stepMm * stepMm
    }

    fun run(): DecoPlan {
        val tissues = Tissues.atSurface(surfaceBar)
        if (!buildProfile()) return result(tissues, 0, 0, null, PlanError.LEVEL_TOO_SHORT)
        loadProfile(tissues)
        val bottomTime = waypoints.last().timeS
        val bottomDepth = waypoints.last().depthMm
        // The ascent is planned from the GF-low anchor this check sets.
        val bottomCeiling = ceilingMm(tissues)

        // Gases already usable at the end of the bottom are switched to there; the shallowest wins.
        val firstAscentGas = switches.filter { it.depthMm > bottomDepth }.minByOrNull { it.depthMm }?.gas
        val gasChanges = switches.filter { it.depthMm <= bottomDepth }.sortedBy { it.depthMm }
        val stopLevels = (DecoPlanner.stopLevels(plan.imperialStops, plan.lastStopDeep).filter { it <= bottomDepth } +
            gasChanges.map { it.depthMm }).distinct().sorted()

        var gas = firstAscentGas ?: waypoints.last().gas
        var depth = bottomDepth
        var clock = bottomTime
        var stopIdx = stopLevels.lastIndex
        var gi = gasChanges.lastIndex
        var lastRate = ascentRate(depth)
        var stopping = false
        var lastSegmentMinSwitch = false
        var lastStopTime = STOP_STEP_S
        var firstStop: Int? = null
        var error: PlanError? = null

        fun mark() = waypoints.add(Waypoint(clock, depth, gas, bottom = false))

        while (error == null) {
            do {
                val rate = ascentRate(depth)
                if (rate != lastRate) {
                    mark()
                    stopping = false
                    lastRate = rate
                }
                var delta = rate * ASCENT_STEP_S
                if (depth - delta < stopLevels[stopIdx]) delta = depth - stopLevels[stopIdx]
                tissues.constantDepth(water.bar(depth), gas, ASCENT_STEP_S)
                lastSegmentMinSwitch = false
                clock += ASCENT_STEP_S
                depth -= delta
            } while (depth > 0 && depth > stopLevels[stopIdx])
            if (depth <= 0) break

            if (gi >= 0 && stopLevels[stopIdx] <= gasChanges[gi].depthMm) {
                val next = gasChanges[gi].gas
                if (gas != next) {
                    mark()
                    stopping = true
                    gas = next
                    if (!lastSegmentMinSwitch && gas.o2 != 1000) {
                        tissues.constantDepth(water.bar(depth), gas, plan.gasSwitchS)
                        clock += plan.gasSwitchS
                        lastSegmentMinSwitch = true
                    }
                }
                gi--
            }
            stopIdx--

            if (!trialAscent(tissues, 0, depth, stopLevels[stopIdx], gas)) {
                if (firstStop == null) firstStop = depth
                if (!stopping) {
                    mark()
                    stopping = true
                }
                val newClock = waitUntil(tissues, clock, clock, lastStopTime * 2 + 1, depth, stopLevels[stopIdx], gas)
                lastStopTime = newClock - clock
                if (lastStopTime >= MAX_STOP_S && depth >= SIX_METRES_MM) {
                    error = PlanError.DECO_TIMEOUT
                } else {
                    tissues.constantDepth(water.bar(depth), gas, lastStopTime)
                    lastSegmentMinSwitch = false
                    clock += lastStopTime
                }
            }
            if (stopping) {
                mark()
                stopping = false
            }
        }
        waypoints.add(Waypoint(clock, 0, gas, bottom = false))
        return result(tissues, bottomTime, bottomCeiling, firstStop, error)
    }

    /** Levels to waypoints: travel at the descent rate, or the stop ascent rate going up. */
    private fun buildProfile(): Boolean {
        var time = 0
        var depth = 0
        waypoints.add(Waypoint(0, 0, plan.bottomGas, bottom = true))
        for (level in plan.levels) {
            val diff = level.depthMm - depth
            val rate = if (diff > 0) plan.descentRate else plan.ascentRates.ascent
            val travel = if (diff == 0) 0 else max(1, (if (diff > 0) diff else -diff) * 60 / rate)
            if (travel > level.durationS) return false
            // Leave the surface on a travel gas while the bottom gas is hypoxic.
            if (depth == 0 && plan.travelGas && ppO2Mbar(plan.bottomGas, 0) < MIN_PPO2 * 1000) {
                val switchMm = bottomGasBreathableMm()
                val gas = travelGas(switchMm)
                if (gas != null && switchMm in 1 until level.depthMm) {
                    waypoints.add(Waypoint(time + max(1, switchMm * 60 / plan.descentRate), switchMm, gas, bottom = true))
                }
            }
            if (travel > 0) waypoints.add(Waypoint(time + travel, level.depthMm, plan.bottomGas, bottom = true))
            time += level.durationS
            if (level.durationS > travel) waypoints.add(Waypoint(time, level.depthMm, plan.bottomGas, bottom = true))
            depth = level.depthMm
        }
        return true
    }

    private fun loadProfile(tissues: Tissues) {
        waypoints.zipWithNext { a, b ->
            tissues.linearChange(water.bar(a.depthMm), water.bar(b.depthMm), b.gas, b.timeS - a.timeS)
        }
    }

    /** mm/s. */
    private fun ascentRate(depthMm: Int): Int = if (depthMm > SIX_METRES_MM) ascentMmPerS else lastSixMetresMmPerS

    /** Ceiling depth, mm, after moving the GF-low anchor as Subsurface does on every check. */
    private fun ceilingMm(tissues: Tissues): Int {
        tissues.anchorGfLow(plan.gf)
        return water.ceilingMm(tissues.toleratedAmbientBar(plan.gf, surfaceBar))
    }

    /** Whether, after waiting [waitS], the ascent from [fromMm] to [toMm] stays below the ceiling. */
    private fun trialAscent(tissues: Tissues, waitS: Int, fromMm: Int, toMm: Int, gas: BreathingGas): Boolean {
        val trial = tissues.copy()
        if (waitS > 0) trial.constantDepth(water.bar(fromMm), gas, waitS)
        var depth = fromMm
        while (depth > toMm) {
            val delta = minOf(ascentRate(depth) * ASCENT_STEP_S, depth)
            trial.constantDepth(water.bar(depth), gas, ASCENT_STEP_S)
            if (ceilingMm(trial) > depth - delta) return false
            depth -= delta
        }
        return true
    }

    /**
     * Earliest clock time, on a whole [STOP_STEP_S], from which the ascent to [toMm] is clear.
     * [leap] guesses how far past [min] that is; the search tests at the upper bound.
     */
    private tailrec fun waitUntil(tissues: Tissues, clock: Int, min: Int, leap: Int, depthMm: Int, toMm: Int, gas: BreathingGas): Int {
        if (min >= MAX_STOP_S) return TIMEOUT_STOP_S
        val upper = min + leap + STOP_STEP_S - 1 - (min + leap - 1) % STOP_STEP_S
        if (!trialAscent(tissues, upper - clock, depthMm, toMm, gas)) {
            return waitUntil(tissues, clock, upper, leap, depthMm, toMm, gas)
        }
        if (upper - min <= STOP_STEP_S) return upper
        return waitUntil(tissues, clock, min, leap / 2, depthMm, toMm, gas)
    }

    private fun result(tissues: Tissues, bottomTime: Int, bottomCeiling: Int, firstStop: Int?, error: PlanError?): DecoPlan {
        val segments = waypoints.zipWithNext { a, b -> PlanSegment(a.timeS, b.timeS, a.depthMm, b.depthMm, b.gas, b.bottom) }
            .filter { it.durationS > 0 }
        return DecoPlan(
            segments = segments,
            runtimeS = waypoints.last().timeS,
            bottomTimeS = bottomTime,
            bottomCeilingMm = bottomCeiling,
            firstStopMm = firstStop,
            gasUse = gasUse(segments),
            cnsPercent = round(segments.sumOf { cns(it) }).toInt(),
            otu = round(segments.sumOf { otu(it) }).toInt(),
            warnings = warnings(segments),
            error = error,
        )
    }

    /** Ambient pressure in atm, for gas volumes at the surface. */
    private fun atm(depthMm: Int): Double = water.bar(depthMm) / ATM_BAR

    private fun gasUse(segments: List<PlanSegment>): List<GasUse> =
        segments.groupBy { it.gas }.map { (gas, segs) ->
            fun litres(s: PlanSegment): Double {
                val sac = if (s.bottom) plan.bottomSacMlPerMin else plan.decoSacMlPerMin
                return atm((s.startDepthMm + s.endDepthMm) / 2) * sac / 60.0 * s.durationS / 1000.0
            }
            GasUse(gas, segs.sumOf { litres(it) }, segs.filter { !it.bottom }.sumOf { litres(it) })
        }

    private fun ppO2Mbar(gas: BreathingGas, depthMm: Int): Int = round(gas.o2 * water.bar(depthMm)).toInt()

    /** CNS %, from the NOAA table fitted as two exponentials over the segment's mean pO2. */
    private fun cns(s: PlanSegment): Double {
        val po2 = (ppO2Mbar(s.gas, s.startDepthMm) + ppO2Mbar(s.gas, s.endDepthMm)) / 2
        if (po2 <= 500) return 0.0
        val rate = if (po2 <= 1500) exp(-11.7853 + 0.00193873 * po2) else exp(-23.6349 + 0.00980829 * po2)
        return s.durationS * rate * 100.0
    }

    /** OTU, Baker's continuous approximation, counting only time above 0.5 bar pO2. */
    private fun otu(s: PlanSegment): Double {
        var po2i = ppO2Mbar(s.gas, s.startDepthMm)
        var po2f = ppO2Mbar(s.gas, s.endDepthMm)
        var t = s.durationS
        if (po2i <= 500 && po2f <= 500) return 0.0
        if (po2i <= 500) {
            t = t * (po2f - 500) / (po2f - po2i)
            po2i = 501
        } else if (po2f <= 500) {
            t = t * (po2i - 500) / (po2i - po2f)
            po2f = 501
        }
        val pm = (po2f + po2i) / 1000.0 - 1.0
        val d = (po2f - po2i).toDouble()
        return t / 60.0 * pm.pow(5.0 / 6.0) * (1.0 - 5.0 * d * d / 216000000.0 / (pm * pm))
    }

    /** pO2 and END checks at every waypoint, the worst per gas and kind. */
    private fun warnings(segments: List<PlanSegment>): List<PlanWarning> {
        val found = mutableListOf<PlanWarning>()
        val checks = segments.flatMap { s -> listOf(s to s.startDepthMm, s to s.endDepthMm) }
        for ((s, depth) in checks) {
            val ppO2 = s.gas.o2 / 1000.0 * water.bar(depth)
            val limit = (if (s.bottom) plan.bottomPpO2Mbar else plan.decoPpO2Mbar) / 1000.0
            // A deco gas at or above the switch depth chosen for it is as planned, even when
            // rounding to the stop step puts it a little over the limit (O2 at 6 m is 1.619 bar).
            val asSwitched = !s.bottom && switches.any { it.gas == s.gas && depth <= it.depthMm }
            if (ppO2 > limit && !asSwitched) found += PlanWarning.HighPpO2(s.gas, depth, ppO2, limit)
            if (ppO2 < MIN_PPO2) found += PlanWarning.LowPpO2(s.gas, depth, ppO2)
            plan.maxEndMm?.let { maxEnd ->
                val end = water.depthMm(water.mbar(depth) * (1000 - s.gas.he) / 1000)
                if (end > maxEnd) found += PlanWarning.HighEnd(s.gas, depth, end)
            }
        }
        return found.groupBy { it.gas to it::class }.values.map { list ->
            when (list.first()) {
                is PlanWarning.LowPpO2 -> list.minBy { it.depthMm }
                else -> list.maxBy { it.depthMm }
            }
        }
    }
}
