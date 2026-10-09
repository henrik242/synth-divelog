package no.synth.divelog.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import no.synth.divelog.ui.dive.ProfileGraph
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.gas.AscentRates
import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.DecoPlan
import no.synth.divelog.core.gas.DecoPlanner
import no.synth.divelog.core.gas.DivePlan
import no.synth.divelog.core.gas.GradientFactors
import no.synth.divelog.core.gas.PlanError
import no.synth.divelog.core.gas.PlanLevel
import no.synth.divelog.core.gas.PlanSegment
import no.synth.divelog.core.gas.PlanWarning
import no.synth.divelog.core.gas.Water
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.units.LITRES_PER_CUFT
import no.synth.divelog.core.model.units.MM_PER_FOOT
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.components.CheckRow
import no.synth.divelog.ui.components.DecimalField
import no.synth.divelog.ui.components.OptionPicker
import no.synth.divelog.ui.components.ToolCard
import no.synth.divelog.ui.components.parseDecimal
import no.synth.divelog.ui.components.trimmedDecimal
import no.synth.divelog.ui.components.twoDecimals
import kotlin.math.round

private const val ML_PER_CUFT = LITRES_PER_CUFT * 1000

/** One bottom level, as typed: depth in the state's units, minutes including the travel to it. */
@Stable
class PlannerLevel(depth: String, minutes: String) {
    var depth by mutableStateOf(depth)
    var minutes by mutableStateOf(minutes)
}

/** A gas as typed, in percent. */
@Stable
class PlannerGas(o2: String, he: String) {
    var o2 by mutableStateOf(o2)
    var he by mutableStateOf(he)

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

/** GUE's standard gases, from the leanest bottom gas to oxygen. */
private val STANDARD_GASES = listOf(
    BreathingGas(100, 700), BreathingGas(150, 550), BreathingGas(180, 450), BreathingGas(210, 350),
    BreathingGas(320), BreathingGas(500), BreathingGas(1000),
)

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

    /** The plan for the inputs, or null while some field is invalid. */
    fun plan(): DivePlan? {
        val levels = levels.map { l ->
            PlanLevel(depthMm(l.depth) ?: return null, minutes(l.minutes) ?: return null)
        }
        val parsed = gases.map { it.parsed() ?: return null }
        val bottomAt = bottomIndex() ?: return null
        val bottom = parsed[bottomAt]
        val deco = parsed.filterIndexed { i, _ -> i != bottomAt }
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

@Composable
fun DivePlannerScreen(state: DivePlannerState, unitSystem: UnitSystem) {
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
        SavedPlansCard(state)

        ToolCard("Bottom profile") {
            state.levels.forEachIndexed { i, level ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DecimalField(level.depth, { level.depth = it }, "Depth ($unit)", Modifier.weight(1f), state.depthMm(level.depth) == null)
                    DecimalField(level.minutes, { level.minutes = it }, "Time (min)", Modifier.weight(1f), state.minutes(level.minutes) == null)
                    if (state.levels.size > 1) {
                        IconButton(onClick = { state.levels.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = "Remove level") }
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
            val bottomAt = state.bottomIndex()
            var decoNumber = 0
            state.gases.forEachIndexed { i, gas ->
                val label = if (i == bottomAt) "Bottom" else "Deco ${++decoNumber}"
                GasFields(label, gas, onRemove = { state.gases.removeAt(i) })
            }
            StandardGasChips(STANDARD_GASES, isSelected = { gas -> state.gases.any { it.parsed() == gas } }, onClick = { gas ->
                val present = state.gases.indexOfFirst { it.parsed() == gas }
                if (present >= 0) state.gases.removeAt(present) else state.gases.add(PlannerGas.of(gas))
            }) {
                TextButton(onClick = { state.gases.add(PlannerGas("", "0")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add gas")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPicker("Bottom pO2 (bar)", state.bottomPpO2, listOf("1.2", "1.3", "1.4", "1.5", "1.6"), Modifier.weight(1f)) { state.bottomPpO2 = it }
                OptionPicker("Deco pO2 (bar)", state.decoPpO2, listOf("1.4", "1.5", "1.6"), Modifier.weight(1f)) { state.decoPpO2 = it }
            }
            Hint(
                "The gas with the least O2 is the bottom gas. The others are switched to at the stop where they " +
                    "reach the deco pO2, or shallower to stay within the max END. A bottom gas too lean for the " +
                    "surface is reached on the leanest other gas that is.",
            )
        }

        ToolCard("Settings") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.gfLow, { state.gfLow = it }, "GF low", Modifier.weight(1f), !state.gfValid())
                DecimalField(state.gfHigh, { state.gfHigh = it }, "GF high", Modifier.weight(1f), !state.gfValid())
            }
            if (!state.gfValid()) ErrorText("GF low and high must be 10-100, low at most high.")
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
            CheckRow("Salt water", state.saltWater) { state.saltWater = it }
        }

        when {
            state.gases.isEmpty() -> Hint("Add a gas to see a plan.")
            result == null -> ErrorText("Fix the fields marked in red to see a plan.")
            result.error == PlanError.LEVEL_TOO_SHORT -> ErrorText("A level is shorter than the travel to it.")
            else -> {
                ResultCard(result, state.units)
                if (result.warnings.isNotEmpty()) WarningsCard(result.warnings, state.units)
                ProfileCard(result, state.units)
                RuntimeTable(result, state.units, state.wholeMinuteStops) { state.wholeMinuteStops = it }
            }
        }
    }
}

/** Saved plans to load or delete, and saving the current one under a name. */
@Composable
private fun SavedPlansCard(state: DivePlannerState) {
    var naming by remember { mutableStateOf<String?>(null) }
    ToolCard("Saved plans") {
        state.saved.forEachIndexed { i, plan ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.load(plan.inputs) }, modifier = Modifier.weight(1f)) {
                    Text(plan.name, Modifier.fillMaxWidth())
                }
                IconButton(onClick = { state.saved.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = "Delete plan") }
            }
        }
        TextButton(onClick = { naming = state.summary() }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Save plan")
        }
    }
    naming?.let { name ->
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Save plan") },
            text = { OutlinedTextField(name, { naming = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    // Saving under an existing name replaces that plan.
                    state.saved.removeAll { it.name == name.trim() }
                    state.saved += SavedPlan(name.trim(), state.toJson())
                    naming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
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

/** One-tap chips for standard [gases] after any [leading] content; [isSelected] marks the ones in use. */
@Composable
private fun StandardGasChips(
    gases: List<BreathingGas>,
    isSelected: (BreathingGas) -> Boolean,
    onClick: (BreathingGas) -> Unit,
    leading: @Composable () -> Unit = {},
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        leading()
        gases.forEach { gas ->
            FilterChip(selected = isSelected(gas), onClick = { onClick(gas) }, label = { Text(gasLabel(gas)) })
        }
    }
}

/** "32 %", "21/35", "O2". */
private fun gasLabel(gas: BreathingGas): String = when {
    gas.o2 == 1000 -> "O2"
    gas.he == 0 -> "${gas.o2 / 10} %"
    else -> "${gas.o2 / 10}/${gas.he / 10}"
}

@Composable
private fun GasFields(label: String, gas: PlannerGas, onRemove: (() -> Unit)?) {
    val invalid = gas.parsed() == null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DecimalField(gas.o2, { gas.o2 = it }, "$label O2 %", Modifier.weight(1f), invalid)
        DecimalField(gas.he, { gas.he = it }, "He %", Modifier.weight(1f), invalid)
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove gas") }
    }
    if (invalid) ErrorText("O2 must be above 0 and O2 + He at most 100 %.")
}

@Composable
private fun ResultCard(plan: DecoPlan, units: UnitSystem) {
    ToolCard("Plan", MaterialTheme.colorScheme.secondaryContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Runtime", minutes(plan.runtimeS), Modifier.weight(1f))
            Tile("TTS", minutes(plan.ttsS), Modifier.weight(1f))
            Tile("First stop", plan.firstStopMm?.let { depth(it, units) } ?: "None", Modifier.weight(1f))
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
            Text(
                "${use.gas.name}: ${volume(use.litres, units)}" +
                    if (use.decoLitres > 0 && use.decoLitres < use.litres) " (${volume(use.decoLitres, units)} on the ascent)" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(text, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * One table line: consecutive ascent segments on one gas are merged. With [foldAscents] the
 * ascents are left out and their time counted in the stop they lead to, the way runtime
 * schedules read: each stop is left on a whole minute of runtime, so its time is whole minutes.
 */
private class TableLine(val phase: String, val depthMm: Int, var durationS: Int, var runtimeS: Int, val gas: BreathingGas, val switched: Boolean)

private fun tableLines(plan: DecoPlan, foldAscents: Boolean): List<TableLine> {
    val lines = mutableListOf<TableLine>()
    var gas: BreathingGas? = null
    var pendingS = 0
    for (s in plan.segments) {
        val phase = phaseOf(s)
        if (foldAscents && phase == "Ascent") {
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
            lines[lines.lastIndex] = TableLine(last.phase, s.endDepthMm, last.durationS + s.durationS, s.endS, s.gas, last.switched)
        } else {
            lines += TableLine(phase, s.endDepthMm, s.durationS, s.endS, s.gas, gas != null && gas != s.gas)
        }
        gas = s.gas
    }
    // The climb from the last stop has no stop after it to count in.
    if (pendingS > 0) lines += TableLine("Ascent", 0, pendingS, plan.runtimeS, plan.segments.last().gas, false)
    return lines
}

private fun phaseOf(s: PlanSegment): String = when {
    s.endDepthMm > s.startDepthMm -> "Descent"
    s.endDepthMm < s.startDepthMm -> "Ascent"
    s.bottom -> "Level"
    else -> "Stop"
}

@Composable
private fun RuntimeTable(plan: DecoPlan, units: UnitSystem, wholeMinuteStops: Boolean, onWholeMinuteStops: (Boolean) -> Unit) {
    ToolCard("Runtime table") {
        CheckRow("Whole-minute stops", wholeMinuteStops, onChange = onWholeMinuteStops)
        TableRow(header = true, cells = listOf("", "Depth", "Time", "Run", "Gas"))
        HorizontalDivider()
        tableLines(plan, foldAscents = wholeMinuteStops).forEach { line ->
            TableRow(
                header = false,
                cells = listOf(line.phase, depth(line.depthMm, units), Format.duration(line.durationS), minutes(line.runtimeS), line.gas.name),
                bold = line.phase == "Stop",
                gasSwitched = line.switched,
            )
        }
        Hint(
            if (wholeMinuteStops) {
                "Each stop's time includes the climb to it, so stops are whole minutes. Run is the runtime when you leave."
            } else {
                "Time is the segment's length, Run the runtime at its end. Stops end on whole minutes of runtime."
            },
        )
    }
}

@Composable
private fun TableRow(header: Boolean, cells: List<String>, bold: Boolean = false, gasSwitched: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    val weights = listOf(1.1f, 1f, 0.9f, 0.9f, 1f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.forEachIndexed { i, text ->
            val isGas = i == cells.lastIndex
            Text(
                text,
                Modifier.weight(weights[i]),
                style = style,
                fontWeight = if (bold || (isGas && gasSwitched)) FontWeight.SemiBold else null,
                color = if (isGas && gasSwitched) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    ElevatedCard(modifier, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error)

/** Whole metres or feet: stops sit on whole units. */
private fun depth(mm: Int, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) "${round(mm / 1000.0).toInt()} m" else "${round(mm / MM_PER_FOOT).toInt()} ft"

private fun volume(litres: Double, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) "${round(litres).toInt()} L" else "${trimmedDecimal(litres * 1000 / ML_PER_CUFT)} cuft"

/** Runtime in minutes, rounded up as dive tables show it. */
private fun minutes(seconds: Int): String = "${(seconds + 59) / 60} min"
