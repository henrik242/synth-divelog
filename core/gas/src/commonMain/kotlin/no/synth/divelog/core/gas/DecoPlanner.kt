// SPDX-License-Identifier: GPL-2.0-only
// The decompression planner follows Subsurface's implementation (core/planner.cpp,
// core/plannernotes.cpp, core/divelist.cpp).
package no.synth.divelog.core.gas

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/** Water density as salinity in g per 10 l, Subsurface's unit. */
enum class Water(val salinity: Int) { SALT(10300), FRESH(10000) }

/** One level of the bottom profile: go to [depthMm] and leave it [durationS] later, travel included. */
data class PlanLevel(val depthMm: Int, val durationS: Int)

/**
 * Ascent rates in mm/min, chosen by depth relative to the average depth of the bottom
 * profile: deeper than 75 % of it, deeper than 50 %, below 6 m, and the last 6 m.
 */
data class AscentRates(
    val deep75: Int = 9000,
    val mid50: Int = 9000,
    val stops: Int = 9000,
    val last6m: Int = 9000,
) {
    init {
        require(listOf(deep75, mid50, stops, last6m).all { it >= 60 }) { "Ascent rates must be at least 60 mm/min" }
    }
}

/** A decompression plan's inputs. Defaults are Subsurface's planner defaults. */
data class DivePlan(
    val levels: List<PlanLevel>,
    val bottomGas: BreathingGas,
    val decoGases: List<BreathingGas> = emptyList(),
    val gf: GradientFactors = GradientFactors(0.30, 0.75),
    val bottomPpO2Mbar: Int = 1400,
    /** Deco gases are switched to at the depth where they reach this pO2. */
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
    /** END above this is warned about; O2 counts as narcotic. Null for no END check. */
    val maxEndMm: Int? = 30000,
) {
    init {
        require(levels.isNotEmpty()) { "A plan needs at least one level" }
        require(levels.all { it.depthMm > 0 && it.durationS > 0 }) { "Levels need a depth and a duration" }
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

    /** pO2 above the limit (bottom or deco), in atm as Subsurface reports it. */
    data class HighPpO2(override val gas: BreathingGas, override val depthMm: Int, val ppO2: Double, val limit: Double) : PlanWarning

    /** pO2 below 0.16: hypoxic. */
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

/** Depth/pressure conversion at a surface pressure and salinity, rounded to whole mbar as Subsurface does. */
class WaterColumn(val surfaceMbar: Int, water: Water) {
    private val mbarPerMm = water.salinity * 0.981 / 100000.0

    fun mbar(depthMm: Int): Int = round(surfaceMbar + depthMm * mbarPerMm).toInt()
    fun bar(depthMm: Int): Double = mbar(depthMm) / 1000.0

    /** Depth of an ambient pressure, mm; negative above the surface. */
    fun depthMm(ambientMbar: Int): Int = round((ambientMbar - surfaceMbar) / mbarPerMm).toInt()

    /** Smooth ceiling depth for a tolerated ambient pressure, never above the surface. */
    fun ceilingMm(toleratedBar: Double): Int {
        val delta = max(0.0, toleratedBar - surfaceMbar / 1000.0)
        return round(round(delta * 1000) / mbarPerMm).toInt()
    }

    /**
     * Depth where [gas] reaches [ppO2Mbar], in whole [stepMm]. Rounds down, except that from
     * 0.9 of a step it rounds up, so oxygen at 1.6 lands on 6 m.
     */
    fun switchDepthMm(gas: BreathingGas, ppO2Mbar: Int, stepMm: Int): Int {
        val depth = depthMm(ppO2Mbar * 1000 / gas.o2).toDouble()
        return (depth / stepMm + 0.1).toInt() * stepMm
    }
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
            )).map { ftToMm(it) }
        } else {
            ((0..90 step 3) + (100..200 step 10) + (220..380 step 20)).map { it * 1000 }
        }
        // A 6 m last stop drops the 3 m level.
        return if (lastStopDeep) levels.filterIndexed { i, _ -> i != 1 } else levels
    }

    internal fun ftToMm(ft: Int): Int = round(ft * 304.8).toInt()
}

private class Waypoint(val timeS: Int, val depthMm: Int, val gas: BreathingGas, val bottom: Boolean)

private class GasChange(val depthMm: Int, val gas: BreathingGas)

private class PlanRun(private val plan: DivePlan) {
    private val water = WaterColumn(plan.surfaceMbar, plan.water)
    private val surfaceBar = plan.surfaceMbar / 1000.0
    private val waypoints = mutableListOf<Waypoint>()
    private var avgDepthMm = 0
    private val rates = with(plan.ascentRates) { listOf(deep75, mid50, stops, last6m).map { it / 60 } }

    fun run(): DecoPlan {
        val tissues = Tissues.atSurface(surfaceBar)
        if (!buildProfile()) return result(tissues, 0, 0, null, PlanError.LEVEL_TOO_SHORT)
        loadProfile(tissues)
        avgDepthMm = averageDepthMm()
        val bottomTime = waypoints.last().timeS
        val bottomDepth = waypoints.last().depthMm
        val bottomCeiling = water.ceilingMm(tissues.toleratedAmbientBar(plan.gf, surfaceBar))

        val stepMm = if (plan.imperialStops) DecoPlanner.ftToMm(10) else 3000
        val switches = plan.decoGases.map { GasChange(water.switchDepthMm(it, plan.decoPpO2Mbar, stepMm), it) }
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
            val rate = if (diff > 0) plan.descentRate else plan.ascentRates.stops
            val travel = if (diff == 0) 0 else max(1, (if (diff > 0) diff else -diff) * 60 / rate)
            if (travel > level.durationS) return false
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

    /** Time-weighted mean depth of the planned profile, in whole mm as Subsurface sums it. */
    private fun averageDepthMm(): Int {
        var integral = 0L
        waypoints.zipWithNext { a, b -> integral += (a.depthMm + b.depthMm).toLong() * (b.timeS - a.timeS) / 2 }
        val last = waypoints.last().timeS
        return if (last > 0) (integral / last).toInt() else 0
    }

    /** mm/s. */
    private fun ascentRate(depthMm: Int): Int = when {
        depthMm.toLong() * 4 > avgDepthMm.toLong() * 3 -> rates[0]
        depthMm.toLong() * 2 > avgDepthMm -> rates[1]
        depthMm > SIX_METRES_MM -> rates[2]
        else -> rates[3]
    }

    /** Whether, after waiting [waitS], the ascent from [fromMm] to [toMm] stays below the ceiling. */
    private fun trialAscent(tissues: Tissues, waitS: Int, fromMm: Int, toMm: Int, gas: BreathingGas): Boolean {
        val trial = tissues.copy()
        if (waitS > 0) trial.constantDepth(water.bar(fromMm), gas, waitS)
        var depth = fromMm
        while (depth > toMm) {
            val delta = minOf(ascentRate(depth) * ASCENT_STEP_S, depth)
            trial.constantDepth(water.bar(depth), gas, ASCENT_STEP_S)
            if (water.ceilingMm(trial.toleratedAmbientBar(plan.gf, surfaceBar)) > depth - delta) return false
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

    private fun atm(depthMm: Int): Double = water.mbar(depthMm) / 1013.25

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
            val ppO2 = s.gas.o2 / 1000.0 * atm(depth)
            val limit = (if (s.bottom) plan.bottomPpO2Mbar else plan.decoPpO2Mbar) / 1000.0
            if (ppO2 > limit) found += PlanWarning.HighPpO2(s.gas, depth, ppO2, limit)
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
