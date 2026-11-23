package no.synth.divelog.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round
import no.synth.divelog.core.gas.AscentRates
import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.Cylinder
import no.synth.divelog.core.gas.CylinderUse
import no.synth.divelog.core.gas.DecoPlan
import no.synth.divelog.core.gas.DecoPlanner
import no.synth.divelog.core.gas.DivePlan
import no.synth.divelog.core.gas.GasSuggestion
import no.synth.divelog.core.gas.GasUse
import no.synth.divelog.core.gas.GradientFactors
import no.synth.divelog.core.gas.PlanError
import no.synth.divelog.core.gas.PlanLevel
import no.synth.divelog.core.gas.PlanSegment
import no.synth.divelog.core.gas.PlanWarning
import no.synth.divelog.core.gas.TANK_PRESETS
import no.synth.divelog.core.gas.TankPreset
import no.synth.divelog.core.gas.Water
import no.synth.divelog.core.gas.WaterColumn
import no.synth.divelog.core.gas.combined
import no.synth.divelog.core.gas.cylinderUse
import no.synth.divelog.core.gas.nominalCubicFeet
import no.synth.divelog.core.gas.suggestGases
import no.synth.divelog.core.gas.usedInTurn
import no.synth.divelog.core.gas.waterLitres
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.BAR_PER_PSI
import no.synth.divelog.core.model.units.LITRES_PER_CUFT
import no.synth.divelog.core.model.units.MM_PER_FOOT
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.LocalStatusColors
import no.synth.divelog.ui.components.CheckRow
import no.synth.divelog.ui.components.CollapsibleToolCard
import no.synth.divelog.ui.components.DecimalField
import no.synth.divelog.ui.components.OptionPicker
import no.synth.divelog.ui.components.StandardGasChips
import no.synth.divelog.ui.components.TapField
import no.synth.divelog.ui.components.ToolCard
import no.synth.divelog.ui.components.parseDecimal
import no.synth.divelog.ui.components.trimmedDecimal
import no.synth.divelog.ui.components.twoDecimals
import no.synth.divelog.ui.dive.ProfileGraph

private const val ML_PER_CUFT = LITRES_PER_CUFT * 1000

/**
 * Most cylinders added to one mix: by Suggest gases (past it a note says the gas runs into the
 * reserve), as Short lines, and in "needs N more".
 */
private const val MAX_ADDED_CYLINDERS = 10

/** One bottom level, as typed: depth in the state's units, minutes including the travel to it. */
@Stable
class PlannerLevel(depth: String, minutes: String) {
    var depth by mutableStateOf(depth)
    var minutes by mutableStateOf(minutes)
}

/**
 * A cylinder as typed, or none when both are blank: [size] in litres of water (metric) or
 * nominal cuft at [pressure] (imperial), [pressure] the fill in bar or psi.
 */
@Stable
class PlannerCylinder(size: String = "", pressure: String = "") {
    var size by mutableStateOf(size)
    var pressure by mutableStateOf(pressure)

    fun isSet(): Boolean = size.isNotBlank() || pressure.isNotBlank()

    fun setTo(other: PlannerCylinder) {
        size = other.size
        pressure = other.pressure
    }

    fun sameAs(other: PlannerCylinder): Boolean = size.trim() == other.size.trim() && pressure.trim() == other.pressure.trim()
}

/** A gas as typed, in percent, and its optional [cylinder]. */
@Stable
class PlannerGas(o2: String, he: String, size: String = "", pressure: String = "") {
    var o2 by mutableStateOf(o2)
    var he by mutableStateOf(he)
    val cylinder = PlannerCylinder(size, pressure)
    var size: String
        get() = cylinder.size
        set(v) { cylinder.size = v }
    var pressure: String
        get() = cylinder.pressure
        set(v) { cylinder.pressure = v }

    fun hasCylinder(): Boolean = cylinder.isSet()

    fun parsed(): BreathingGas? {
        val o2 = parseDecimal(o2) ?: return null
        val he = parseDecimal(he) ?: return null
        return BreathingGas.ofPercent(o2, he)?.takeIf { it.o2 > 0 }
    }

    fun set(gas: BreathingGas) {
        o2 = trimmedDecimal(gas.o2 / 10.0)
        he = trimmedDecimal(gas.he / 10.0)
    }

    companion object {
        fun of(gas: BreathingGas) = PlannerGas("", "").apply { set(gas) }
    }
}

/**
 * Planner inputs, as typed, in [units]: depths m or ft, rates per minute, SAC L or cuft per
 * minute. Defaults are Subsurface's. Held above the screen so they survive leaving the tab.
 */
@Stable
class DivePlannerState {
    var units by mutableStateOf(UnitSystem.METRIC)
        internal set
    val levels = mutableStateListOf(PlannerLevel("45", "25"))
    /** The gases on the dive; none at first, so no plan until one is added. See [bottomIndex]. */
    val gases = mutableStateListOf<PlannerGas>()

    /** The bottom gas is the leanest in O2 (on a tie, the most helium); the rest are deco gases. */
    fun bottomIndex(): Int? = gases.indices
        .filter { gases[it].parsed() != null }
        .minWithOrNull(compareBy({ gases[it].parsed()?.o2 }, { -(gases[it].parsed()?.he ?: 0) }))

    /**
     * "Bottom" for the first cylinder of the bottom mix and "Bottom stage" for more of it;
     * "Deco 1", "Deco 2" by mix, so two of one deco mix share a number.
     */
    fun gasLabels(): Map<PlannerGas, String> {
        val bottom = bottomIndex()?.let { gases[it].parsed() }
        val firstBottom = gases.firstOrNull { bottom != null && it.parsed() == bottom }
        val decoMixes = gasesInOrder().map { it.parsed() }.filter { it != bottom }.distinct()
        return gases.associateWith { gas ->
            val mix = gas.parsed()
            when {
                gas === firstBottom -> "Bottom"
                mix != null && mix == bottom -> "Bottom stage"
                else -> "Deco ${decoMixes.indexOf(mix) + 1}"
            }
        }
    }

    /** The gases as listed: the bottom gas, the deco gases from the deepest switch up, then any not valid. */
    fun gasesInOrder(): List<PlannerGas> =
        gases.sortedWith(compareBy({ it.parsed()?.o2 ?: Int.MAX_VALUE }, { -(it.parsed()?.he ?: 0) }))

    /**
     * The cylinders a gas starts with when added, by its role; none when not set. See [addGas].
     * A deco gas is carried in a stage cylinder, as is bottom gas beyond the back-mounted set.
     */
    val bottomCylinder = PlannerCylinder()
    val stageCylinder = PlannerCylinder()

    /**
     * Adds [gas] with the default cylinder for its role, unless it has one. When it becomes the
     * bottom gas, the gas it takes over from trades the bottom default, if it still has that,
     * for the stage one.
     */
    fun addGas(gas: PlannerGas) {
        val previousBottom = bottomIndex()?.let { gases[it] }
        gases += gas
        val bottomMix = bottomIndex()?.let { gases[it].parsed() }
        val isBottom = bottomMix != null && gas.parsed() == bottomMix
        if (!gas.hasCylinder()) gas.cylinder.setTo(if (isBottom) bottomCylinder else stageCylinder)
        // A new, leaner bottom mix; another cylinder of the same one changes nothing.
        if (isBottom && previousBottom != null && previousBottom.parsed() != bottomMix && previousBottom.cylinder.sameAs(bottomCylinder)) {
            previousBottom.cylinder.setTo(stageCylinder)
        }
    }

    /** Gas kept back in each cylinder; see [reserveBar]. */
    var reserve by mutableStateOf(Reserve.NONE)

    /** Whether the settings card is open; folded at first, as the defaults suit most plans. */
    var settingsExpanded by mutableStateOf(false)

    /** Whether the runtime table shows every line, or one line per gas when folded. */
    var runtimeExpanded by mutableStateOf(true)
    var bottomPpO2 by mutableStateOf("1.4")
    var decoPpO2 by mutableStateOf("1.6")
    var gfLow by mutableStateOf("30")
    var gfHigh by mutableStateOf("75")
    var descentRate by mutableStateOf("18")
    var ascentRate by mutableStateOf("9")
    var lastAscentRate by mutableStateOf("9")
    var lastStopDeep by mutableStateOf(false)
    var saltWater by mutableStateOf(true)
    var wholeMinuteStops by mutableStateOf(true)
    /** Plans the user kept; kept with the inputs by the app. */
    val saved = mutableStateListOf<SavedPlan>()

    /** "45 m 25 min 21/35": a default name for saving the current plan. */
    fun summary(): String {
        val unit = if (units == UnitSystem.METRIC) "m" else "ft"
        val depth = levels.mapNotNull { parseDecimal(it.depth) }.maxOrNull()?.let { "${trimmedDecimal(it)} $unit" }
        val minutes = levels.sumOf { parseDecimal(it.minutes) ?: 0.0 }.takeIf { it > 0 }?.let { "${trimmedDecimal(it)} min" }
        val bottom = bottomIndex()?.let { gases[it].parsed()?.name }
        return listOfNotNull(depth, minutes, bottom).joinToString(" ").ifEmpty { "Plan" }
    }
    var surfaceMbar by mutableStateOf("1013")
    var bottomSac by mutableStateOf("20")
    var decoSac by mutableStateOf("17")
    var maxEnd by mutableStateOf("30")

    /** Show the fields in [system], converting what is typed. */
    fun useUnits(system: UnitSystem) {
        if (system == units) return
        val toFeet = system == UnitSystem.IMPERIAL
        fun length(text: String): String = parseDecimal(text)?.let { v ->
            val mm = if (toFeet) v * 1000 else v * MM_PER_FOOT
            trimmedDecimal(if (toFeet) mm / MM_PER_FOOT else mm / 1000)
        } ?: text
        fun sac(text: String): String = parseDecimal(text)?.let { v ->
            if (toFeet) twoDecimals(v * 1000 / ML_PER_CUFT) else trimmedDecimal(v * ML_PER_CUFT / 1000)
        } ?: text
        levels.forEach { it.depth = length(it.depth) }
        (gases.map { it.cylinder } + bottomCylinder + stageCylinder).forEach { convertCylinder(it, toFeet) }
        descentRate = length(descentRate)
        ascentRate = length(ascentRate)
        lastAscentRate = length(lastAscentRate)
        maxEnd = length(maxEnd)
        bottomSac = sac(bottomSac)
        decoSac = sac(decoSac)
        units = system
    }

    private val metric get() = units == UnitSystem.METRIC

    fun depthMm(text: String): Int? = parseDecimal(text)?.takeIf { it > 0 }
        ?.let { round(if (metric) it * 1000 else it * MM_PER_FOOT).toInt() }
        ?.takeIf { it <= 300_000 }

    fun minutes(text: String): Int? = parseDecimal(text)?.takeIf { it > 0 && it <= 600 }?.let { round(it * 60).toInt() }

    fun rate(text: String): Int? = depthMm(text)?.takeIf { it >= 1000 }

    fun sacMl(text: String): Int? = parseDecimal(text)?.takeIf { it > 0 && it < 100 }
        ?.let { round(if (metric) it * 1000 else it * ML_PER_CUFT).toInt() }

    fun gf(text: String): Int? = parseDecimal(text)?.takeIf { it >= 10 && it <= 100 }?.let { round(it).toInt() }

    fun gfValid(): Boolean {
        val low = gf(gfLow) ?: return false
        val high = gf(gfHigh) ?: return false
        return low <= high
    }

    fun surface(): Int? = parseDecimal(surfaceMbar)?.takeIf { it >= 500 && it <= 1100 }?.let { round(it).toInt() }

    fun maxEndMm(): Int? = if (maxEnd.isBlank()) null else depthMm(maxEnd)

    /** Whether every field on the settings card is valid. */
    fun settingsValid(): Boolean =
        gfValid() && listOf(descentRate, ascentRate, lastAscentRate).all { rate(it) != null } &&
            sacMl(bottomSac) != null && sacMl(decoSac) != null && surface() != null &&
            maxEndValid() && !cylinderInvalid(bottomCylinder) && !cylinderInvalid(stageCylinder)

    /**
     * "GF 30/75, pO2 1.4/1.6, SAC 20/17 L/min, reserve 1/3, salt water, bottom 24 L 232 bar":
     * the settings on one line, the default cylinders when set.
     */
    fun settingsSummary(): String {
        val sacUnit = if (metric) "L/min" else "cuft/min"
        return listOfNotNull(
            "GF $gfLow/$gfHigh",
            "pO2 $bottomPpO2/$decoPpO2",
            "SAC $bottomSac/$decoSac $sacUnit",
            if (reserve == Reserve.NONE) "no reserve" else "reserve ${reserve.label(units)}",
            if (saltWater) "salt water" else "fresh water",
            bottomCylinder.takeIf { it.isSet() }?.let { "bottom ${cylinderText(it.size, it.pressure, units)}" },
            stageCylinder.takeIf { it.isSet() }?.let { "stage ${cylinderText(it.size, it.pressure, units)}" },
        ).joinToString(", ")
    }

    /**
     * [gas]'s cylinder in the other units. Without a fill the size cannot be converted, so it is
     * cleared. A cylinder that is a common one, within rounding, takes that one's figures.
     */
    private fun convertCylinder(cylinder: PlannerCylinder, toFeet: Boolean) {
        val pressure = parseDecimal(cylinder.pressure)
        if (pressure == null) {
            cylinder.size = ""
            return
        }
        val size = parseDecimal(cylinder.size)
        val fromMetric = toFeet
        val litres = size?.let { if (fromMetric) it else waterLitres(it, pressure) }
        val bar = if (fromMetric) pressure else pressure * BAR_PER_PSI
        val target = if (toFeet) UnitSystem.IMPERIAL else UnitSystem.METRIC
        val preset = litres?.let { presetMatching(it, bar, target) }
        val (newSize, newPressure) = preset ?: cylinderFields(litres, bar, target)
        cylinder.size = newSize
        cylinder.pressure = newPressure
    }

    /** [gas]'s cylinder as water litres and fill bar, or null when not given or out of range. */
    private fun cylinderSize(cylinder: PlannerCylinder): Pair<Double, Double>? {
        val size = parseDecimal(cylinder.size)?.takeIf { it > 0 } ?: return null
        val pressure = parseDecimal(cylinder.pressure)?.takeIf { it > 0 } ?: return null
        val litres = if (metric) size else waterLitres(size, pressure)
        val bar = if (metric) pressure else pressure * BAR_PER_PSI
        return (litres to bar).takeIf { litres <= MAX_CYLINDER_LITRES && bar <= MAX_FILL_BAR }
    }

    /** A cylinder is optional, but one half given or out of range is an error. */
    fun cylinderInvalid(cylinder: PlannerCylinder): Boolean = cylinder.isSet() && cylinderSize(cylinder) == null

    /** The cylinders of each mix, for the gas used; a mix listed twice has both. */
    fun cylinders(): Map<BreathingGas, List<Cylinder>> = gases.mapNotNull { gas ->
        val mix = gas.parsed() ?: return@mapNotNull null
        cylinderSize(gas.cylinder)?.let { (litres, bar) -> Cylinder(litres, bar, mix) }
    }.groupBy { it.gas }

    /** Gauge bar kept back in [cylinder]. */
    fun reserveBar(cylinder: Cylinder): Double = when (reserve) {
        Reserve.THIRDS -> cylinder.pressureBar / 3
        Reserve.FIXED -> if (metric) FIXED_RESERVE_BAR else FIXED_RESERVE_PSI * BAR_PER_PSI
        Reserve.NONE -> 0.0
    }

    /** Fills [gas] from [preset]; a twin set as its total. */
    /** Whether [cylinder] is [preset], within half a percent. */
    fun isPreset(cylinder: PlannerCylinder, preset: PlannerPreset): Boolean {
        val (litres, bar) = cylinderSize(cylinder) ?: return false
        return abs(litres - preset.litres) <= preset.litres * 0.005 && abs(bar - preset.bar) <= preset.bar * 0.005
    }

    fun choose(cylinder: PlannerCylinder, preset: PlannerPreset) {
        val (size, pressure) = cylinderFields(preset.litres, preset.bar, units)
        cylinder.size = size
        cylinder.pressure = pressure
    }

    private fun ppO2Mbar(text: String): Int? = parseDecimal(text)?.let { round(it * 1000).toInt() }

    private fun water(): Water = if (saltWater) Water.SALT else Water.FRESH

    private fun maxEndValid(): Boolean = maxEnd.isBlank() || maxEndMm() != null

    /** The deepest level, mm, or null while a level is invalid. */
    private fun deepestMm(): Int? = levels.map { depthMm(it.depth) ?: return null }.maxOrNull()

    /** Whether the fields Suggest gases reads are valid: the levels, pO2s, max END and surface. */
    private fun suggestionInputsValid(): Boolean =
        deepestMm() != null && ppO2Mbar(bottomPpO2) != null && ppO2Mbar(decoPpO2) != null && maxEndValid() && surface() != null

    /**
     * Gases for the profile's deepest level within the pO2 and END settings, or null while a
     * level or one of those settings is invalid, or when no mix fits.
     */
    fun gasSuggestion(): GasSuggestion? = suggestGases(
        maxDepthMm = deepestMm() ?: return null,
        bottomPpO2Mbar = ppO2Mbar(bottomPpO2) ?: return null,
        decoPpO2Mbar = ppO2Mbar(decoPpO2) ?: return null,
        maxEndMm = if (maxEndValid()) maxEndMm() else return null,
        water = WaterColumn(surface() ?: return null, water()),
        imperialStops = !metric,
    )

    /** Why Suggest gases is off when no field shows it already: no mix fits the limits. */
    fun gasSuggestionProblem(): String? =
        if (suggestionInputsValid() && gasSuggestion() == null) "No mix fits the pO2 and END limits at the deepest level." else null

    /** Notes on the last Suggest gases (empty when all went to plan), and the gases before it, for Undo. */
    class AppliedSuggestion(val note: String, val before: List<PlannerGas>, val after: List<PlannerGas>)

    private var lastSuggestion by mutableStateOf<AppliedSuggestion?>(null)

    /** The last suggestion while the gases are still the ones it made. */
    fun appliedSuggestion(): AppliedSuggestion? = lastSuggestion?.takeIf { it.after == gases.toList() }

    /** Puts back the gases from before the last suggestion. */
    fun undoSuggestion() {
        val applied = appliedSuggestion() ?: return
        gases.clear()
        gases += applied.before
        lastSuggestion = null
    }

    /**
     * Replaces the gases with [suggestion]. Each gas takes the default cylinder for its role:
     * the bottom cylinder for the bottom gas, a stage for a deco gas. Without that default set,
     * the bottom gas keeps the old bottom gas's cylinder, and a deco gas a cylinder of its mix
     * if one was listed (never one of the old bottom mix). Without deco stops the
     * deco gases are left out, unless the bottom gas needs a travel gas. Then each mix gets the
     * cylinders the plan's gas use takes; see [addCylindersForGasUsed].
     */
    fun applySuggestion(suggestion: GasSuggestion) {
        val before = gases.toList()
        val oldBottomMix = bottomIndex()?.let { gases[it].parsed() }
        fun gas(mix: BreathingGas, default: PlannerCylinder, oldOne: (BreathingGas?) -> Boolean) = PlannerGas.of(mix).also { gas ->
            val from = default.takeIf { cylinderSize(it) != null }
                ?: before.firstOrNull { oldOne(it.parsed()) && cylinderSize(it.cylinder) != null }?.cylinder
            from?.let { gas.cylinder.setTo(it) }
        }
        val bottom = gas(suggestion.bottom, bottomCylinder) { oldBottomMix != null && it == oldBottomMix }
        val deco = suggestion.deco.map { mix -> gas(mix, stageCylinder) { it == mix && it != oldBottomMix } }
        gases.clear()
        addGas(bottom)
        val notes = mutableListOf<String>()
        val noStops = plan()?.let { DecoPlanner.plan(it).firstStopMm == null } == true
        val needsTravelGas = surface()?.let { WaterColumn(it, water()).hypoxicAtSurface(suggestion.bottom) } != false
        if (deco.isNotEmpty() && noStops && !needsTravelGas) {
            notes += "No deco stops, so no deco gases."
        } else {
            deco.forEach(::addGas)
        }
        notes += plan()?.let { addCylindersForGasUsed(DecoPlanner.plan(it).gasUse, it.bottomGas) }.orEmpty()
        lastSuggestion = AppliedSuggestion(notes.joinToString(" "), before, gases.toList())
    }

    /**
     * How many more cylinders like the last of [cylinders] the [litres] take for the set to keep
     * its reserve; null when more would not do, or take more than [MAX_ADDED_CYLINDERS].
     */
    fun moreCylindersNeeded(cylinders: List<Cylinder>, litres: Double): Int? {
        val set = cylinders.toMutableList()
        var more = 0
        while (!setUse(set, litres).keepsReserve) {
            if (more == MAX_ADDED_CYLINDERS) return null
            set += cylinders.last()
            more++
        }
        return more
    }

    /** [litres] of gas from [cylinders] of one mix, breathed as one set that keeps its reserve. */
    fun setUse(cylinders: List<Cylinder>, litres: Double): CylinderUse {
        val set = combined(cylinders)
        return cylinderUse(set, litres, reserveBar(set))
    }

    /**
     * Adds cylinders of each mix until the gas the plan uses leaves the set its reserve: more
     * bottom gas in [stageCylinder]s (or, with none set, copies of the bottom one), more of a
     * deco gas in copies of its stage. Gas use does not depend on the cylinders, so it plans
     * once. Returns notes on what it could not add.
     */
    private fun addCylindersForGasUsed(gasUse: List<GasUse>, bottomMix: BreathingGas): List<String> {
        val notes = mutableListOf<String>()
        for (use in gasUse) {
            val first = gases.firstOrNull { it.parsed() == use.gas && cylinderSize(it.cylinder) != null } ?: continue
            val template = stageCylinder.takeIf { use.gas == bottomMix && cylinderSize(it) != null } ?: first.cylinder
            val (litres, bar) = cylinderSize(template) ?: continue
            val extra = Cylinder(litres, bar, use.gas)
            val set = cylinders()[use.gas].orEmpty().toMutableList()
            if (setUse(set, use.litres).keepsReserve) continue
            if (cylinderUse(extra, 0.0, reserveBar(extra)).usableLitres <= 0) {
                notes += "A ${use.gas.name} cylinder holds nothing above the reserve."
                continue
            }
            var count = 0
            while (count < MAX_ADDED_CYLINDERS && !setUse(set, use.litres).keepsReserve) {
                set += extra
                gases += PlannerGas(first.o2, first.he, template.size, template.pressure)
                count++
            }
            if (!setUse(set, use.litres).keepsReserve) notes += "${use.gas.name} still runs into the reserve."
        }
        return notes
    }

    /** The plan for the inputs, or null while some field is invalid. */
    fun plan(): DivePlan? {
        val levels = levels.map { l ->
            PlanLevel(depthMm(l.depth) ?: return null, minutes(l.minutes) ?: return null)
        }
        val parsed = gases.map { it.parsed() ?: return null }
        val bottom = parsed[bottomIndex() ?: return null]
        // The bottom mix listed again is not a deco gas.
        val deco = parsed.filter { it != bottom }.distinct()
        if (!gfValid()) return null
        val ascent = rate(ascentRate) ?: return null
        val last = rate(lastAscentRate) ?: return null
        if (!maxEndValid()) return null
        return DivePlan(
            levels = levels,
            bottomGas = bottom,
            decoGases = deco,
            gf = GradientFactors((gf(gfLow) ?: return null) / 100.0, (gf(gfHigh) ?: return null) / 100.0),
            bottomPpO2Mbar = ppO2Mbar(bottomPpO2) ?: return null,
            decoPpO2Mbar = ppO2Mbar(decoPpO2) ?: return null,
            descentRate = rate(descentRate) ?: return null,
            ascentRates = AscentRates(ascent, last),
            lastStopDeep = lastStopDeep,
            imperialStops = !metric,
            water = water(),
            surfaceMbar = surface() ?: return null,
            bottomSacMlPerMin = sacMl(bottomSac) ?: return null,
            decoSacMlPerMin = sacMl(decoSac) ?: return null,
            maxEndMm = maxEndMm(),
        )
    }
}

/** How much gas each cylinder keeps back: a third of the fill, a fixed pressure, or none. */
enum class Reserve(val label: (UnitSystem) -> String) {
    THIRDS({ "1/3" }),
    FIXED({ if (it == UnitSystem.METRIC) "${FIXED_RESERVE_BAR.toInt()} bar" else "${FIXED_RESERVE_PSI.toInt()} psi" }),
    NONE({ "None" }),
}

private const val FIXED_RESERVE_BAR = 50.0
private const val FIXED_RESERVE_PSI = 700.0
private const val MAX_CYLINDER_LITRES = 100.0
private const val MAX_FILL_BAR = 400.0

/** A preset's size and fill as typed in [units]; a twin set as its total. */
private fun presetFields(preset: TankPreset, units: UnitSystem): Pair<String, String> {
    val count = if (preset.doubles) 2 else 1
    return if (units == UnitSystem.METRIC) {
        val tank = preset.metric ?: preset.imperial?.toMetric() ?: return "" to ""
        trimmedDecimal(tank.litres * count) to trimmedDecimal(tank.bar)
    } else {
        val tank = preset.imperial ?: preset.metric?.toImperial() ?: return "" to ""
        trimmedDecimal(tank.cubicFeet * count) to round(tank.psi).toLong().toString()
    }
}

/**
 * A cylinder of [litres] (null when not typed) at [bar] as typed in [units]. Metric keeps two
 * decimals so imperial survives a round trip: 3000 psi is 206.84 bar.
 */
private fun cylinderFields(litres: Double?, bar: Double, units: UnitSystem): Pair<String, String> =
    if (units == UnitSystem.METRIC) {
        fun text(v: Double) = twoDecimals(v).trimEnd('0').trimEnd('.')
        (litres?.let { text(it) } ?: "") to text(bar)
    } else {
        (litres?.let { trimmedDecimal(nominalCubicFeet(it, bar)) } ?: "") to round(bar / BAR_PER_PSI).toLong().toString()
    }

/** A cylinder the planner offers by name: [litres] of water volume in all, filled to [bar]. */
data class PlannerPreset(val name: String, val litres: Double, val bar: Double)

val PLANNER_PRESETS = listOf(
    PlannerPreset("D12 232 bar", 24.0, 232.0),
    PlannerPreset("D7 300 bar", 14.0, 300.0),
    PlannerPreset("D8.5 232 bar", 17.0, 232.0),
    PlannerPreset("S80 200 bar", 11.1, 200.0),
    PlannerPreset("S40 200 bar", 5.7, 200.0),
    PlannerPreset("7 L 200 bar", 7.0, 200.0),
)

/**
 * The fields of a common cylinder within half a percent of [litres] at [bar], preferring one
 * rated in [units]; null when none is that close.
 */
private fun presetMatching(litres: Double, bar: Double, units: UnitSystem): Pair<String, String>? {
    fun close(a: Double, b: Double) = abs(a - b) <= b * 0.005
    val native = { p: TankPreset -> if (units == UnitSystem.METRIC) p.metric != null else p.imperial != null }
    return TANK_PRESETS.sortedByDescending(native).firstOrNull { p ->
        val tank = p.metric ?: p.imperial?.toMetric() ?: return@firstOrNull false
        val count = if (p.doubles) 2 else 1
        close(litres, tank.litres * count) && close(bar, tank.bar)
    }?.let { presetFields(it, units) }
}

@Composable
fun DivePlannerScreen(
    state: DivePlannerState,
    unitSystem: UnitSystem,
    onTopBarActions: ((@Composable RowScope.() -> Unit)?) -> Unit = {},
) {
    var naming by remember { mutableStateOf<String?>(null) }
    // Save sits in the top bar while the planner is shown.
    val actions: @Composable RowScope.() -> Unit = remember(state) {
        { TextButton(onClick = { naming = state.summary() }) { Text("Save plan") } }
    }
    DisposableEffect(actions) {
        onTopBarActions(actions)
        onDispose { onTopBarActions(null) }
    }
    naming?.let { SavePlanDialog(state, it) { naming = null } }

    // Convert before anything reads the fields, so the frame after a unit switch is already right.
    Snapshot.withoutReadObservation { state.useUnits(unitSystem) }
    val metric = state.units == UnitSystem.METRIC
    val unit = if (metric) "m" else "ft"
    val input = state.plan()
    val result = remember(input) { input?.let { DecoPlanner.plan(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModelNote()
        if (state.saved.isNotEmpty()) SavedPlansPicker(state)

        ToolCard("Bottom profile") {
            state.levels.forEach { level ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DecimalField(level.depth, { level.depth = it }, "Depth ($unit)", Modifier.weight(1f), state.depthMm(level.depth) == null)
                    DecimalField(level.minutes, { level.minutes = it }, "Time (min)", Modifier.weight(1f), state.minutes(level.minutes) == null)
                    if (state.levels.size > 1) {
                        IconButton(onClick = { state.levels.remove(level) }) { Icon(Icons.Filled.Close, contentDescription = "Remove level") }
                    }
                }
            }
            TextButton(onClick = {
                val last = state.levels.last()
                state.levels.add(PlannerLevel(last.depth, "10"))
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add level")
            }
        }

        ToolCard("Gases", action = {
            if (state.appliedSuggestion() != null) {
                TextButton(onClick = { state.undoSuggestion() }) { Text("Undo") }
            }
            val suggestion = state.gasSuggestion()
            TextButton(enabled = suggestion != null, onClick = { suggestion?.let { state.applySuggestion(it) } }) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Text("Suggest gases")
            }
        }) {
            state.gasSuggestionProblem()?.let { Hint(it) }
            state.appliedSuggestion()?.note?.takeIf { it.isNotEmpty() }?.let { note ->
                Text(
                    note,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val labels = state.gasLabels()
            state.gasesInOrder().forEach { gas ->
                val label = labels.getValue(gas)
                key(gas) { GasRow(label, gas, state, onRemove = { state.gases.remove(gas) }) }
            }
            var adding by remember { mutableStateOf(false) }
            if (adding) {
                MixDialog(null, onDismiss = { adding = false }) { o2, he -> state.addGas(PlannerGas(o2, he)) }
            }
            // Chips only add, another cylinder of the mix on each tap; each row has its own remove button.
            StandardGasChips(isSelected = { gas -> state.gases.any { it.parsed() == gas } }, onClick = { gas ->
                state.addGas(PlannerGas.of(gas))
            }) {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add gas")
                }
            }
        }

        CollapsibleToolCard(
            "Settings",
            state.settingsExpanded,
            { state.settingsExpanded = it },
            summary = if (state.settingsValid()) state.settingsSummary() else "Some settings are not valid. Open to fix them.",
            summaryIsError = !state.settingsValid(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.gfLow, { state.gfLow = it }, "GF low", Modifier.weight(1f), !state.gfValid())
                DecimalField(state.gfHigh, { state.gfHigh = it }, "GF high", Modifier.weight(1f), !state.gfValid())
            }
            if (!state.gfValid()) ErrorText("GF low and high must be 10-100, low at most high.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPicker("Bottom pO2 (bar)", state.bottomPpO2, listOf("1.2", "1.3", "1.4", "1.5", "1.6"), Modifier.weight(1f)) { state.bottomPpO2 = it }
                OptionPicker("Deco pO2 (bar)", state.decoPpO2, listOf("1.4", "1.5", "1.6"), Modifier.weight(1f)) { state.decoPpO2 = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.descentRate, { state.descentRate = it }, "Descent ($unit/min)", Modifier.weight(1f), state.rate(state.descentRate) == null)
                DecimalField(state.ascentRate, { state.ascentRate = it }, "Ascent ($unit/min)", Modifier.weight(1f), state.rate(state.ascentRate) == null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(
                    state.lastAscentRate,
                    { state.lastAscentRate = it },
                    "Last ${if (metric) "6 m" else "20 ft"} ($unit/min)",
                    Modifier.weight(1f),
                    state.rate(state.lastAscentRate) == null,
                )
                val shallow = if (metric) "3 m" else "10 ft"
                val deep = if (metric) "6 m" else "20 ft"
                OptionPicker("Last stop", if (state.lastStopDeep) deep else shallow, listOf(shallow, deep), Modifier.weight(1f)) {
                    state.lastStopDeep = it == deep
                }
            }
            val sacUnit = if (metric) "L/min" else "cuft/min"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.bottomSac, { state.bottomSac = it }, "Bottom SAC ($sacUnit)", Modifier.weight(1f), state.sacMl(state.bottomSac) == null)
                DecimalField(state.decoSac, { state.decoSac = it }, "Deco SAC ($sacUnit)", Modifier.weight(1f), state.sacMl(state.decoSac) == null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.surfaceMbar, { state.surfaceMbar = it }, "Surface (mbar)", Modifier.weight(1f), state.surface() == null)
                DecimalField(
                    state.maxEnd,
                    { state.maxEnd = it },
                    "Max END ($unit)",
                    Modifier.weight(1f),
                    state.maxEnd.isNotBlank() && state.maxEndMm() == null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val labels = Reserve.entries.associateBy { it.label(state.units) }
                OptionPicker("Cylinder reserve", state.reserve.label(state.units), labels.keys.toList(), Modifier.weight(1f)) {
                    labels[it]?.let { reserve -> state.reserve = reserve }
                }
                CheckRow("Salt water", state.saltWater, Modifier.weight(1f)) { state.saltWater = it }
            }
            CheckRow("Whole-minute stops in the runtime table", state.wholeMinuteStops) { state.wholeMinuteStops = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultCylinderField("Bottom cylinder", state.bottomCylinder, state, Modifier.weight(1f))
                DefaultCylinderField("Stage cylinder", state.stageCylinder, state, Modifier.weight(1f))
            }
            Hint("A gas you add starts in the cylinder for its role. Deco gases and extra bottom gas go in stages.")
        }

        when {
            state.gases.isEmpty() -> Hint("Add a gas to see a plan.")
            result == null -> ErrorText("Fix the fields marked in red to see a plan.")
            result.error == PlanError.LEVEL_TOO_SHORT -> ErrorText("A level is shorter than the travel to it.")
            else -> {
                if (result.warnings.isNotEmpty()) WarningsCard(result.warnings, state.units)
                ResultCard(result, state)
                ProfileCard(result, state.units)
                RuntimeTable(result, state)
            }
        }
    }
}

/** A pulldown of saved plans: pick one to load it, or delete it from the list. */
@Composable
private fun SavedPlansPicker(state: DivePlannerState) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TapField("Load a saved plan", "", Modifier.fillMaxWidth(), trailingIcon = Icons.Filled.ArrowDropDown) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.saved.forEach { plan ->
                DropdownMenuItem(
                    text = { Text(plan.name) },
                    onClick = {
                        state.load(plan.inputs)
                        open = false
                    },
                    trailingIcon = {
                        IconButton(onClick = { state.saved.remove(plan) }) { Icon(Icons.Filled.Close, contentDescription = "Delete ${plan.name}") }
                    },
                )
            }
        }
    }
}

/** Asks for a name, prefilled from [initial], and saves the current plan under it. */
@Composable
private fun SavePlanDialog(state: DivePlannerState, initial: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Save plan") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                // Saving under an existing name replaces that plan.
                state.saved.removeAll { it.name == name.trim() }
                state.saved += SavedPlan(name.trim(), state.toJson())
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** The planned profile, with a line at each gas switch. */
@Composable
private fun ProfileCard(plan: DecoPlan, units: UnitSystem) {
    val (samples, events) = remember(plan) { profileOf(plan) }
    ToolCard("Profile") {
        ProfileGraph(samples, events, units, Modifier.fillMaxWidth())
    }
}

/** Depth every [PROFILE_STEP_S] along the plan, and its gas switches as events. */
private fun profileOf(plan: DecoPlan): Pair<List<Sample>, List<Event>> {
    val samples = mutableListOf(Sample(0, 0))
    for (s in plan.segments) {
        var t = s.startS + PROFILE_STEP_S
        while (t < s.endS) {
            val depth = s.startDepthMm + (s.endDepthMm - s.startDepthMm) * (t - s.startS) / s.durationS
            samples += Sample(t, depth)
            t += PROFILE_STEP_S
        }
        samples += Sample(s.endS, s.endDepthMm)
    }
    val events = plan.segments.zipWithNext().filter { (a, b) -> a.gas != b.gas }.map { (_, b) ->
        Event(b.startS, EventType.GAS_SWITCH, ((b.gas.o2 / 10).toLong() shl 8) or (b.gas.he / 10).toLong())
    }
    return samples to events
}

private const val PROFILE_STEP_S = 30

@Composable
private fun ModelNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Buhlmann ZHL-16C with gradient factors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One gas on a line: its mix and its cylinder, each edited in a dialog. */
@Composable
private fun GasRow(label: String, gas: PlannerGas, state: DivePlannerState, onRemove: () -> Unit) {
    var editMix by remember { mutableStateOf(false) }
    var editCylinder by remember { mutableStateOf(false) }
    val mix = gas.parsed()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TapField(label, mix?.name ?: "${gas.o2}/${gas.he}", Modifier.weight(1f), isError = mix == null) { editMix = true }
        // Without a cylinder the label alone asks for one.
        TapField(
            if (gas.hasCylinder()) "Cylinder" else "Add cylinder",
            cylinderText(gas.size, gas.pressure, state.units),
            Modifier.weight(1.4f),
            isError = state.cylinderInvalid(gas.cylinder),
        ) { editCylinder = true }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove $label ${mix?.name ?: "gas"}") }
    }
    if (editMix) {
        MixDialog(gas, onDismiss = { editMix = false }) { o2, he ->
            gas.o2 = o2
            gas.he = he
        }
    }
    if (editCylinder) CylinderDialog("Cylinder for ${mix?.name ?: "this gas"}", gas.cylinder, state) { editCylinder = false }
}

/** "24 L 232 bar", "80 cuft 3000", as typed; empty without a cylinder. Psi is left off to fit. */
internal fun cylinderText(size: String, pressure: String, units: UnitSystem): String = when {
    size.isBlank() && pressure.isBlank() -> ""
    units == UnitSystem.METRIC -> "$size L $pressure bar".trim()
    else -> "$size cuft $pressure".trim()
}

/** "24 L 232 bar", "80 cuft 3000 psi": a cylinder for the gas used, whole numbers but for a part litre. */
internal fun cylinderText(cylinder: Cylinder, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) {
        "${trimmedDecimal(cylinder.volumeLitres)} L ${pressure(cylinder.pressureBar, units)}"
    } else {
        "${round(nominalCubicFeet(cylinder.volumeLitres, cylinder.pressureBar)).toLong()} cuft ${pressure(cylinder.pressureBar, units)}"
    }

/** Edits a mix, or adds one when [initial] is null; [onSave] gets O2 and He as typed. */
@Composable
private fun MixDialog(initial: PlannerGas?, onDismiss: () -> Unit, onSave: (o2: String, he: String) -> Unit) {
    val draft = remember { PlannerGas(initial?.o2 ?: "", initial?.he ?: "0") }
    val valid = draft.parsed() != null
    // A new gas starts blank; it is not wrong until something is typed.
    val showError = !valid && draft.o2.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add gas" else "Edit gas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(draft.o2, { draft.o2 = it }, "O2 %", Modifier.weight(1f), showError)
                    DecimalField(draft.he, { draft.he = it }, "He %", Modifier.weight(1f), showError)
                }
                if (showError) ErrorText("O2 must be above 0 and O2 + He at most 100 %.")
                StandardGasChips(isSelected = { it == draft.parsed() }, onClick = { draft.set(it) })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(draft.o2, draft.he)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A default cylinder in the settings: tap to change it; "None" when not set. */
@Composable
private fun DefaultCylinderField(label: String, cylinder: PlannerCylinder, state: DivePlannerState, modifier: Modifier) {
    var editing by remember { mutableStateOf(false) }
    TapField(
        label,
        cylinderText(cylinder.size, cylinder.pressure, state.units).ifEmpty { "None" },
        modifier,
        isError = state.cylinderInvalid(cylinder),
    ) { editing = true }
    if (editing) CylinderDialog("Default ${label.lowercase()}", cylinder, state) { editing = false }
}

/** Edits [cylinder]: a common one from the menu, or size and fill; "No cylinder" clears it. */
@Composable
private fun CylinderDialog(title: String, cylinder: PlannerCylinder, state: DivePlannerState, onDismiss: () -> Unit) {
    val draft = remember { PlannerCylinder(cylinder.size, cylinder.pressure) }
    val metric = state.units == UnitSystem.METRIC
    val invalid = state.cylinderInvalid(draft)
    fun save(size: String, pressure: String) {
        cylinder.size = size
        cylinder.pressure = pressure
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PLANNER_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = state.isPreset(draft, preset),
                            onClick = { state.choose(draft, preset) },
                            label = { Text(preset.name) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(draft.size, { draft.size = it }, if (metric) "Size (L)" else "Size (cuft)", Modifier.weight(1f), invalid)
                    DecimalField(draft.pressure, { draft.pressure = it }, if (metric) "Fill (bar)" else "Fill (psi)", Modifier.weight(1f), invalid)
                }
                if (invalid) {
                    ErrorText(
                        if (metric) {
                            "Give both size and fill: up to ${MAX_CYLINDER_LITRES.toInt()} L and ${MAX_FILL_BAR.toInt()} bar."
                        } else {
                            "Give both size and fill: up to ${round(MAX_FILL_BAR / BAR_PER_PSI).toInt()} psi, " +
                                "and at most ${MAX_CYLINDER_LITRES.toInt()} L of water volume."
                        },
                    )
                }
                Hint(
                    if (metric) {
                        "Water volume; twins as the total, 24 L. The fill as it reads in the water: a warm fill drops as it cools."
                    } else {
                        "Nominal capacity, 80 for an AL80; twins as the total. The fill as it reads in the water: a warm fill drops as it cools."
                    },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !invalid, onClick = { save(draft.size.trim(), draft.pressure.trim()) }) { Text("OK") }
        },
        dismissButton = {
            Row {
                if (cylinder.isSet()) TextButton(onClick = { save("", "") }) { Text("No cylinder") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ResultCard(plan: DecoPlan, state: DivePlannerState) {
    val units = state.units
    val cylinders = state.cylinders()
    ToolCard("Plan") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Runtime", ceilMinutes(plan.runtimeS).toString(), "min", Modifier.weight(1f))
            Tile("TTS", ceilMinutes(plan.ttsS).toString(), "min", Modifier.weight(1f))
            val stop = plan.firstStopMm?.let { depth(it, units) }
            Tile("First stop", stop?.substringBefore(' ') ?: "None", stop?.substringAfter(' '), Modifier.weight(1f))
        }
        if (plan.error == PlanError.DECO_TIMEOUT) ErrorText("A stop would last more than two days; the plan is cut short.")
        Text(
            "CNS ${plan.cnsPercent} %, OTU ${plan.otu}. Ceiling at end of bottom: " +
                if (plan.bottomCeilingMm > 0) depth(plan.bottomCeilingMm, units) else "none",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()
        Text("Gas used", style = MaterialTheme.typography.labelLarge)
        plan.gasUse.forEach { use ->
            val list = cylinders[use.gas].orEmpty()
            Row(verticalAlignment = Alignment.Bottom) {
                Text(use.gas.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(volume(use.litres, units), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (list.isNotEmpty()) {
                // Breathed in turn, each on its own line, then any gas they are short of; one note
                // under them speaks for the set.
                val whole = state.setUse(list, use.litres)
                SetLines(list, use.litres, whole, units)
                ShortLines(list.last(), use.litres - whole.availableLitres, units)
                SetNote(whole, state.moreCylindersNeeded(list, use.litres), units)
            }
        }
        if (plan.gasUse.any { cylinders[it.gas] != null }) UsageLegend(state.reserve, units)
    }
}

/** What the [UsageBar] colours mean, and the reserve setting behind the amber. */
@Composable
private fun UsageLegend(reserve: Reserve, units: UnitSystem) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val keeps = reserve != Reserve.NONE
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem("Spare", colors.outlineVariant, thick = true)
        LegendItem("Gas used", colors.primary, thick = false)
        if (keeps) LegendItem("Reserve", status.warningContainer, thick = true)
        if (keeps) LegendItem("Used from the reserve", status.warning, thick = false)
        LegendItem("Short", colors.error, thick = false)
    }
    Hint(
        when (reserve) {
            Reserve.THIRDS -> "Reserve: a third of the fill is kept back."
            Reserve.FIXED -> "Reserve: ${reserve.label(units)} is kept back."
            Reserve.NONE -> "No reserve is kept back."
        } + " Change it under Settings, Cylinder reserve.",
    )
}

/** A legend swatch drawn like the bar it explains: [thick] for the cylinder, thin for what is on it. */
@Composable
private fun LegendItem(label: String, color: Color, thick: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.width(16.dp).height(14.dp)) {
            val height = if (thick) size.height else size.height * 0.45f
            drawRoundRect(color, Offset(0f, (size.height - height) / 2), Size(size.width, height), CornerRadius(height / 2))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A stretch of a [UsageBar], as fractions of the cylinder: on the wide bar when [wide], else on the thin one. */
private class BarPart(val from: Float, val to: Float, val color: Color, val wide: Boolean = false)

/**
 * A mix's [cylinders], one line each, breathed in turn. Across them in order come the gas
 * used, the reserve after it on the wide bar, and the spare gas, so the spare ends the last
 * cylinder. Gas used from the reserve is amber; what each has left is from [usedInTurn].
 */
@Composable
private fun SetLines(cylinders: List<Cylinder>, litres: Double, set: CylinderUse, units: UnitSystem) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val total = set.availableLitres
    val reserve = total - set.usableLitres
    val used = minOf(litres, total)
    val reserveStart = minOf(used, total - reserve)
    var start = 0.0
    usedInTurn(cylinders, litres, reserve).forEach { use ->
        val size = use.availableLitres
        // A stretch of the set, in litres, cut to this cylinder and scaled to its bar.
        fun part(from: Double, to: Double, color: Color, wide: Boolean = false): BarPart? {
            val a = maxOf(from, start)
            val b = minOf(to, start + size)
            return if (b > a && size > 0) BarPart(((a - start) / size).toFloat(), ((b - start) / size).toFloat(), color, wide) else null
        }
        val parts = listOfNotNull(
            part(reserveStart, reserveStart + reserve, status.warningContainer, wide = true),
            part(0.0, reserveStart, colors.primary),
            part(total - reserve, used, status.warning),
        )
        start += size
        UsageRow(
            cylinderText(use.cylinder, units),
            colors.onSurfaceVariant,
            { UsageBar(parts, it) },
            if (use.enough) pressure(use.endBar, units) else "empty",
            if (use.keepsReserve) Color.Unspecified else useColor(use),
        )
    }
}

/**
 * Gas past the cylinders: [shortLitres] on lines of more cylinders [like] the last, each bar
 * full but for the last, with the pressure it would take from such a cylinder.
 */
@Composable
private fun ShortLines(like: Cylinder, shortLitres: Double, units: UnitSystem) {
    if (shortLitres <= 0) return
    val holds = cylinderUse(like, 0.0).availableLitres
    if (holds <= 0) return
    var left = shortLitres
    repeat(minOf(ceil(shortLitres / holds).toInt(), MAX_ADDED_CYLINDERS)) {
        val litres = minOf(left, holds)
        left -= litres
        val error = MaterialTheme.colorScheme.error
        UsageRow(
            "Short",
            error,
            { UsageBar(listOf(BarPart(0f, (litres / holds).toFloat(), error)), it) },
            pressure(cylinderUse(like, litres).usedBar, units),
            error,
        )
    }
}

/** A gas used line: [label] at the left, the [bar], and [end] at the right. */
@Composable
private fun UsageRow(label: String, labelColor: Color, bar: @Composable (Modifier) -> Unit, end: String, endColor: Color) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, color = labelColor, maxLines = 1)
        bar(Modifier.weight(1f))
        Text(
            end,
            Modifier.width(64.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            color = endColor,
        )
    }
}

/** Under a mix's last line: whether its cylinders keep the reserve, and how many more like the last it takes. */
@Composable
private fun SetNote(set: CylinderUse, more: Int?, units: UnitSystem) {
    val note = when {
        set.keepsReserve -> return
        !set.enough -> "Short of gas"
        // A reserve as big as the fill.
        set.usableLitres <= 0 -> "Into the reserve"
        else -> "Below the ${pressure(set.reserveBar, units)} reserve"
    }
    StatusText(note + (more?.takeIf { it > 0 }?.let { " · needs $it more" } ?: ""), useColor(set))
}

@Composable
private fun useColor(use: CylinderUse): Color = when {
    !use.enough -> MaterialTheme.colorScheme.error
    !use.keepsReserve -> LocalStatusColors.current.warning
    else -> MaterialTheme.colorScheme.primary
}

/** A short warning under a cylinder, right-aligned under the pressure it explains, in [color]. */
@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text,
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End,
    )
}

/**
 * A cylinder, a wide grey bar the full width with the reserve on it in light amber, and the
 * [parts] used on a thin bar on top: teal, dark amber from the reserve, red for gas it is short
 * of. Colours keep 3:1 against their neighbours and the card.
 */
@Composable
private fun UsageBar(parts: List<BarPart>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        fun pill(height: Float, width: Float) = Path().apply {
            addRoundRect(RoundRect(0f, (size.height - height) / 2, width, (size.height + height) / 2, CornerRadius(height / 2)))
        }
        fun draw(part: BarPart, top: Float, height: Float) {
            val from = size.width * part.from.coerceIn(0f, 1f)
            val to = size.width * part.to.coerceIn(0f, 1f)
            if (to > from) drawRect(part.color, Offset(from, top), Size(to - from, height))
        }
        val (wide, thinParts) = parts.partition { it.wide }
        clipPath(pill(size.height, size.width)) {
            drawRect(colors.outlineVariant)
            wide.forEach { draw(it, 0f, size.height) }
        }
        val thin = size.height * 0.45f
        val end = size.width * (thinParts.maxOfOrNull { it.to } ?: 0f).coerceIn(0f, 1f)
        clipPath(pill(thin, end)) { thinParts.forEach { draw(it, (size.height - thin) / 2, thin) } }
    }
}

@Composable
private fun WarningsCard(warnings: List<PlanWarning>, units: UnitSystem) {
    ToolCard("Warnings", MaterialTheme.colorScheme.errorContainer) {
        warnings.forEach { w ->
            val text = when (w) {
                is PlanWarning.HighPpO2 ->
                    "pO2 ${twoDecimals(w.ppO2)} on ${w.gas.name} at ${depth(w.depthMm, units)}, above ${twoDecimals(w.limit)}."
                is PlanWarning.LowPpO2 ->
                    "Hypoxic: pO2 ${twoDecimals(w.ppO2)} on ${w.gas.name} at ${depth(w.depthMm, units)}."
                is PlanWarning.HighEnd ->
                    "END ${depth(w.endMm, units)} on ${w.gas.name} at ${depth(w.depthMm, units)}."
                is PlanWarning.NoDecoGas ->
                    "No deco gas: ${hoursMinutes(w.ttsS)} ascent on ${w.gas.name}, first stop ${depth(w.firstStopMm, units)}. " +
                        "Consider a deco gas."
                is PlanWarning.LongRuntime ->
                    "Runtime ${hoursMinutes(w.runtimeS)}: unusually long. Check the depth, time and gases; " +
                        "plan for warmth, water and oxygen exposure."
                is PlanWarning.HighCns -> "CNS ${w.percent} %, above 100 %."
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, contentDescription = "Warning")
                Text(text)
            }
        }
    }
}

/**
 * One table line: consecutive ascent segments on one gas are merged. With [foldAscents] the
 * ascents are left out and their time counted in the stop they lead to, the way runtime
 * schedules read: each stop is left on a whole minute of runtime, so its time is whole minutes.
 */
private class TableLine(
    val phase: String,
    val depthMm: Int,
    var durationS: Int,
    var runtimeS: Int,
    val gas: BreathingGas,
    val switched: Boolean,
    /** Where a descent or ascent starts; its gas is breathed from here. */
    val fromDepthMm: Int = depthMm,
)

private fun tableLines(plan: DecoPlan, foldAscents: Boolean): List<TableLine> {
    val lines = mutableListOf<TableLine>()
    var gas: BreathingGas? = null
    var pendingS = 0
    var pendingFromMm = 0
    for (s in plan.segments) {
        val phase = phaseOf(s)
        if (foldAscents && phase == "Ascent") {
            if (pendingS == 0) pendingFromMm = s.startDepthMm
            pendingS += s.durationS
            continue
        }
        if (foldAscents && phase == "Stop") {
            val last = lines.lastOrNull()
            if (pendingS == 0 && last != null && last.phase == "Stop" && last.depthMm == s.endDepthMm && last.gas == s.gas) {
                // A gas switch and the stop after it at the same depth read as one stop.
                last.durationS += s.durationS
                last.runtimeS = s.endS
            } else {
                lines += TableLine(phase, s.endDepthMm, s.durationS + pendingS, s.endS, s.gas, gas != null && gas != s.gas)
            }
            pendingS = 0
            gas = s.gas
            continue
        }
        val last = lines.lastOrNull()
        if (phase == "Ascent" && last != null && last.phase == "Ascent" && last.gas == s.gas && !s.bottom) {
            lines[lines.lastIndex] = TableLine(last.phase, s.endDepthMm, last.durationS + s.durationS, s.endS, s.gas, last.switched, last.fromDepthMm)
        } else {
            lines += TableLine(phase, s.endDepthMm, s.durationS, s.endS, s.gas, gas != null && gas != s.gas, s.startDepthMm)
        }
        gas = s.gas
    }
    // The climb from the last stop has no stop after it to count in.
    if (pendingS > 0) lines += TableLine("Ascent", 0, pendingS, plan.runtimeS, plan.segments.last().gas, false, pendingFromMm)
    return lines
}

private fun phaseOf(s: PlanSegment): String = when {
    s.endDepthMm > s.startDepthMm -> "Descent"
    s.endDepthMm < s.startDepthMm -> "Ascent"
    s.bottom -> "Level"
    else -> "Stop"
}

/**
 * The part of a dive breathed on one [gas]: from [startS] to [endS] of runtime, starting at
 * [startMm], at most [deepestMm] deep and ending at [endMm], with [stops] stop depths in it.
 * [bottom] when it has time at a level, [descent] when it only goes down.
 */
internal data class GasPhase(
    val gas: BreathingGas,
    val startS: Int,
    val endS: Int,
    val startMm: Int,
    val deepestMm: Int,
    val endMm: Int,
    val bottom: Boolean,
    val descent: Boolean,
    val stops: Int,
) {
    val durationS: Int get() = endS - startS
}

/** [plan]'s segments run together while the gas stays the same. */
internal fun gasPhases(plan: DecoPlan): List<GasPhase> {
    val phases = mutableListOf<GasPhase>()
    var group = mutableListOf<PlanSegment>()
    fun close() {
        val first = group.firstOrNull() ?: return
        val last = group.last()
        val stopDepths = group.filter { it.isStop }.map { it.endDepthMm }.distinct()
        phases += GasPhase(
            first.gas, first.startS, last.endS, first.startDepthMm, group.maxOf { max(it.startDepthMm, it.endDepthMm) },
            last.endDepthMm,
            bottom = group.any { it.bottom && it.startDepthMm == it.endDepthMm },
            descent = group.all { it.endDepthMm > it.startDepthMm },
            stops = stopDepths.size,
        )
        group = mutableListOf()
    }
    plan.segments.forEach { s ->
        if (group.isNotEmpty() && group.last().gas != s.gas) close()
        group += s
    }
    close()
    return phases
}

@Composable
private fun RuntimeTable(plan: DecoPlan, state: DivePlannerState) {
    val units = state.units
    CollapsibleToolCard(
        "Runtime table",
        state.runtimeExpanded,
        { state.runtimeExpanded = it },
        collapsed = {
            TableRow(header = true, cells = tableHeader(units))
            HorizontalDivider()
            gasPhases(plan).forEach { phase ->
                val label = when {
                    phase.bottom -> "Bottom"
                    phase.descent -> "Descent"
                    phase.stops == 1 -> "1 stop"
                    phase.stops > 1 -> "${phase.stops} stops"
                    else -> "Ascent"
                }
                // A descent from where it starts, the rest from their deepest point.
                val from = if (phase.descent) phase.startMm else phase.deepestMm
                val depths = depthRange(from, phase.endMm, units)
                TableRow(
                    header = false,
                    cells = listOf(label, depths, Format.duration(phase.durationS), Format.duration(phase.endS), tableGas(phase.gas)),
                )
            }
            Hint("One line per gas: how long you breathe it, and the runtime when you leave it. Open for every stop.")
        },
    ) {
        val wholeMinuteStops = state.wholeMinuteStops
        TableRow(header = true, cells = tableHeader(units))
        HorizontalDivider()
        tableLines(plan, foldAscents = wholeMinuteStops).forEach { line ->
            TableRow(
                header = false,
                cells = listOf(line.phase, depthRange(line.fromDepthMm, line.depthMm, units), Format.duration(line.durationS), Format.duration(line.runtimeS), tableGas(line.gas)),
                bold = line.phase == "Stop",
                gasSwitched = line.switched,
            )
        }
        Hint(
            if (wholeMinuteStops) {
                "Each stop's duration includes the climb to it, so stops are whole minutes. Runtime is when you leave."
            } else {
                "Runtime is at the end of each line. Stops end on whole minutes of runtime."
            },
        )
    }
}

@Composable
private fun TableRow(header: Boolean, cells: List<String>, bold: Boolean = false, gasSwitched: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val weights = listOf(1.1f, 1.15f, 1.05f, 1f, 0.95f)
    // A gas switch gets a band so it stands out from the stops around it; its text is the band's own colour.
    val band = if (gasSwitched) {
        Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
    } else {
        Modifier
    }
    Row(Modifier.fillMaxWidth().then(band).padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.forEachIndexed { i, text ->
            val isGas = i == cells.lastIndex
            Text(
                text,
                Modifier.weight(weights[i]),
                style = style,
                fontWeight = if (bold || (isGas && gasSwitched)) FontWeight.SemiBold else null,
                color = if (gasSwitched) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified,
            )
        }
    }
}

/** A result: [value] large, its [unit] small after it, so "1312 min" fits a third of a phone. */
@Composable
private fun Tile(label: String, value: String, unit: String?, modifier: Modifier) {
    val colors = CardDefaults.elevatedCardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ElevatedCard(modifier, colors = colors) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                buildAnnotatedString {
                    append(value)
                    unit?.let { withStyle(MaterialTheme.typography.labelLarge.toSpanStyle()) { append(" $it") } }
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error)

/** Whole metres or feet: stops sit on whole units. */
/** A stop or level's depth; a descent or ascent as from-to, so a gas switch reads at its start. */
/** "90-51" or "6", the unit left to the column header so three-digit feet fit. */
private fun depthRange(fromMm: Int, toMm: Int, units: UnitSystem): String {
    fun number(mm: Int) = depth(mm, units).substringBefore(' ')
    return if (fromMm == toMm) number(toMm) else "${number(fromMm)}-${number(toMm)}"
}

private fun tableHeader(units: UnitSystem): List<String> =
    listOf("", if (units == UnitSystem.METRIC) "Depth (m)" else "Depth (ft)", "Duration", "Runtime", "Gas")

private fun depth(mm: Int, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) "${round(mm / 1000.0).toInt()} m" else "${round(mm / MM_PER_FOOT).toInt()} ft"

private fun volume(litres: Double, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) "${grouped(round(litres).toLong())} L" else "${trimmedDecimal(litres * 1000 / ML_PER_CUFT)} cuft"

/** Thousands set apart by a space: 53 112. */
internal fun grouped(v: Long): String {
    val digits = abs(v).toString()
    val head = digits.length % 3
    val groups = listOfNotNull(digits.take(head).ifEmpty { null }) + digits.drop(head).chunked(3)
    return (if (v < 0) "-" else "") + groups.joinToString(" ")
}

/** Whole minutes, rounded up as dive tables show them. */
private fun ceilMinutes(seconds: Int): Int = (seconds + 59) / 60

/** "45 min", "2 h", "7 h 12 min", rounded up to the minute. */
internal fun hoursMinutes(seconds: Int): String {
    val total = ceilMinutes(seconds)
    return when {
        total < 60 -> "$total min"
        total % 60 == 0 -> "${total / 60} h"
        else -> "${total / 60} h ${total % 60} min"
    }
}

/** Gauge pressure in whole bar or psi. */
private fun pressure(bar: Double, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) "${round(bar).toInt()} bar" else "${round(bar / BAR_PER_PSI).toInt()} psi"

/** A gas as short as the table's last column needs: "O2" for oxygen. */
private fun tableGas(gas: BreathingGas): String = if (gas == BreathingGas.OXYGEN) "O2" else gas.name

/** Runtime in minutes, rounded up as dive tables show it. */
