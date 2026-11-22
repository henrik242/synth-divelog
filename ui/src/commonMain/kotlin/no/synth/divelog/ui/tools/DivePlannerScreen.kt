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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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
import no.synth.divelog.core.model.units.feetToMm
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

    /** "Bottom" for every cylinder of the bottom mix, "Deco 1", "Deco 2" by mix, so two of one mix share a number. */
    fun gasLabels(): Map<PlannerGas, String> {
        val bottom = bottomIndex()?.let { gases[it].parsed() }
        val decoMixes = gasesInOrder().map { it.parsed() }.filter { it != bottom }.distinct()
        return gases.associateWith { gas ->
            val mix = gas.parsed()
            if (mix != null && mix == bottom) "Bottom" else "Deco ${decoMixes.indexOf(mix) + 1}"
        }
    }

    /** The gases as listed: the bottom gas, the deco gases from the deepest switch up, then any not valid. */
    fun gasesInOrder(): List<PlannerGas> =
        gases.sortedWith(compareBy({ it.parsed()?.o2 ?: Int.MAX_VALUE }, { -(it.parsed()?.he ?: 0) }))

    /** The cylinders a gas starts with when added, by its role; none when not set. See [addGas]. */
    val bottomCylinder = PlannerCylinder()
    val decoCylinder = PlannerCylinder()

    /**
     * Adds [gas] with the default cylinder for its role, unless it has one. When it becomes the
     * bottom gas, the gas it takes over from trades the bottom default, if it still has that,
     * for the deco one.
     */
    fun addGas(gas: PlannerGas) {
        val previousBottom = bottomIndex()?.let { gases[it] }
        gases += gas
        val bottomMix = bottomIndex()?.let { gases[it].parsed() }
        val isBottom = bottomMix != null && gas.parsed() == bottomMix
        if (!gas.hasCylinder()) gas.cylinder.setTo(if (isBottom) bottomCylinder else decoCylinder)
        // A new, leaner bottom mix; another cylinder of the same one changes nothing.
        if (isBottom && previousBottom != null && previousBottom.parsed() != bottomMix && previousBottom.cylinder.sameAs(bottomCylinder)) {
            previousBottom.cylinder.setTo(decoCylinder)
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
        (gases.map { it.cylinder } + bottomCylinder + decoCylinder).forEach { convertCylinder(it, toFeet) }
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
            (maxEnd.isBlank() || maxEndMm() != null) && !cylinderInvalid(bottomCylinder) && !cylinderInvalid(decoCylinder)

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
            decoCylinder.takeIf { it.isSet() }?.let { "deco ${cylinderText(it.size, it.pressure, units)}" },
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

    /**
     * Gases for the profile's deepest level within the pO2 and END settings, or null while a
     * level or one of those settings is invalid, or when no mix fits.
     */
    fun gasSuggestion(): GasSuggestion? {
        val depth = levels.map { depthMm(it.depth) ?: return null }.maxOrNull() ?: return null
        if (maxEnd.isNotBlank() && maxEndMm() == null) return null
        return suggestGases(
            maxDepthMm = depth,
            bottomPpO2Mbar = round((parseDecimal(bottomPpO2) ?: return null) * 1000).toInt(),
            decoPpO2Mbar = round((parseDecimal(decoPpO2) ?: return null) * 1000).toInt(),
            maxEndMm = maxEndMm(),
            water = WaterColumn(surface() ?: return null, if (saltWater) Water.SALT else Water.FRESH),
            stepMm = if (metric) 3000 else feetToMm(10),
        )
    }

    /**
     * Replaces the gases with [suggestion]. The bottom gas keeps the old bottom gas's cylinder,
     * a deco gas the cylinder of the same mix if one was listed; otherwise each takes the
     * default cylinder for its role.
     */
    fun apply(suggestion: GasSuggestion) {
        val oldBottom = bottomIndex()?.let { gases[it] }
        fun withCylinder(mix: BreathingGas, from: PlannerGas?, default: PlannerCylinder) =
            PlannerGas.of(mix).apply { cylinder.setTo(from?.cylinder ?: default) }
        val bottom = withCylinder(suggestion.bottom, oldBottom, bottomCylinder)
        val deco = suggestion.deco.map { mix ->
            withCylinder(mix, gases.firstOrNull { it !== oldBottom && it.parsed() == mix }, decoCylinder)
        }
        gases.clear()
        gases += bottom
        gases += deco
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
        if (maxEnd.isNotBlank() && maxEndMm() == null) return null
        return DivePlan(
            levels = levels,
            bottomGas = bottom,
            decoGases = deco,
            gf = GradientFactors((gf(gfLow) ?: return null) / 100.0, (gf(gfHigh) ?: return null) / 100.0),
            bottomPpO2Mbar = round((parseDecimal(bottomPpO2) ?: return null) * 1000).toInt(),
            decoPpO2Mbar = round((parseDecimal(decoPpO2) ?: return null) * 1000).toInt(),
            descentRate = rate(descentRate) ?: return null,
            ascentRates = AscentRates(ascent, last),
            lastStopDeep = lastStopDeep,
            imperialStops = !metric,
            water = if (saltWater) Water.SALT else Water.FRESH,
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
            Hint("Time at each level includes the travel to it. Descent at the descent rate.")
            TextButton(onClick = {
                val last = state.levels.last()
                state.levels.add(PlannerLevel(last.depth, "10"))
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add level")
            }
        }

        ToolCard("Gases") {
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
            val suggestion = state.gasSuggestion()
            TextButton(enabled = suggestion != null, onClick = { suggestion?.let { state.apply(it) } }) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Text("Suggest gases")
            }
            Hint(
                "The gas with the least O2 is the bottom gas. The others are switched to at the stop where they " +
                    "reach the deco pO2, or shallower to stay within the max END. A bottom gas too lean for the " +
                    "surface is reached on the leanest other gas that is. Suggest gases replaces the list with a " +
                    "standard bottom gas for the deepest level and deco gases for the ascent.",
            )
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
                DefaultCylinderField("Deco cylinder", state.decoCylinder, state, Modifier.weight(1f))
            }
            Hint("A gas you add starts in the cylinder for its role.")
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
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${mix?.name ?: "gas"}") }
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
            if (list.size == 1) {
                val cylinder = list.single()
                val one = cylinderUse(cylinder, use.litres, state.reserveBar(cylinder))
                CylinderUseLine(one, units, one.cylindersNeeded?.let { "needs $it" })
            } else if (list.size > 1) {
                // Breathed in turn, each on its own line. The last one holds the set's reserve,
                // so its note also says how many such sets the gas takes.
                val set = combined(list)
                val whole = cylinderUse(set, use.litres, state.reserveBar(set))
                val uses = usedInTurn(list, use.litres, whole.availableLitres - whole.usableLitres)
                uses.forEachIndexed { i, one ->
                    CylinderUseLine(one, units, whole.cylindersNeeded?.takeIf { i == uses.lastIndex }?.let { "needs $it sets" })
                }
            }
        }
    }
}

/**
 * One cylinder: its size, a bar of the need over the capacity, and the pressure left. A note
 * only when it runs into the reserve or short, ending in [needs].
 */
@Composable
private fun CylinderUseLine(use: CylinderUse, units: UnitSystem, needs: String?) {
    val color = useColor(use)
    val reserveFraction = if (use.availableLitres > 0) (1 - use.usableLitres / use.availableLitres).toFloat() else 0f
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            cylinderText(use.cylinder, units),
            Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        UsageBar((use.usedLitres / use.availableLitres).toFloat(), reserveFraction, Modifier.weight(1f))
        Text(
            if (use.enough) pressure(use.endBar, units) else "empty",
            Modifier.width(64.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            color = if (use.keepsReserve) Color.Unspecified else color,
        )
    }
    val note = when {
        use.keepsReserve -> return
        !use.enough -> "${pressure(-use.endBar, units)} short"
        // A reserve as big as the fill, or in a set the part of the set's reserve it holds.
        use.usableLitres <= 0 -> "Into the reserve"
        else -> "Below the ${pressure(use.reserveBar, units)} reserve"
    }
    StatusText(note + (needs?.let { " · $it" } ?: ""), color)
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
 * The gas the plan needs, a thin bar, over the cylinder's capacity, a wider one behind it.
 * [used] is the need as a fraction of the capacity; the scale fits whichever is longer, so a
 * need past the capacity runs past its end, marked with a tick. The capacity shows its
 * [reserve] fraction at the right end in amber; the need is teal over the usable gas, amber in
 * the reserve and red past the capacity. Colours keep 3:1 against their neighbours and the card.
 */
@Composable
private fun UsageBar(used: Float, reserve: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val scale = size.width / maxOf(used, 1f)
        val capacityEnd = scale
        val reserveStart = scale * (1 - reserve.coerceIn(0f, 1f))
        val usedEnd = scale * used.coerceAtLeast(0f)
        fun pill(height: Float, width: Float) = Path().apply {
            addRoundRect(RoundRect(0f, (size.height - height) / 2, width, (size.height + height) / 2, CornerRadius(height / 2)))
        }
        // The cylinder: usable gas, then the reserve.
        clipPath(pill(size.height, capacityEnd)) {
            drawRect(colors.outlineVariant, size = Size(reserveStart, size.height))
            drawRect(status.warningContainer, Offset(reserveStart, 0f), Size(capacityEnd - reserveStart, size.height))
        }
        // The need, thinner, on top.
        val thin = size.height * 0.45f
        val top = (size.height - thin) / 2
        clipPath(pill(thin, usedEnd)) {
            fun segment(from: Float, to: Float, color: Color) {
                if (to > from) drawRect(color, Offset(from, top), Size(to - from, thin))
            }
            segment(0f, minOf(usedEnd, reserveStart), colors.primary)
            segment(reserveStart, minOf(usedEnd, capacityEnd), status.warning)
            segment(capacityEnd, usedEnd, colors.error)
        }
        // Where the cylinder ends, so a shortfall shows without telling amber from red.
        if (used > 1f) {
            drawLine(colors.onSurface, Offset(capacityEnd, 0f), Offset(capacityEnd, size.height), strokeWidth = 2.dp.toPx())
        }
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
