package no.synth.divelog.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.format.Format
import kotlin.math.round

private const val MM_PER_FOOT = 304.8
private const val ML_PER_CUFT = 28316.8466

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
        val o2 = parse(o2)?.let { round(it * 10).toInt() } ?: return null
        val he = parse(he)?.let { round(it * 10).toInt() } ?: return null
        if (o2 <= 0 || he < 0 || o2 + he > 1000) return null
        return BreathingGas(o2, he)
    }
}

/**
 * Planner inputs, as typed, in [units]: depths m or ft, rates per minute, SAC L or cuft per
 * minute. Defaults are Subsurface's. Held above the screen so they survive leaving the tab.
 */
@Stable
class DivePlannerState {
    var units by mutableStateOf(UnitSystem.METRIC)
        private set
    val levels = mutableStateListOf(PlannerLevel("45", "25"))
    val bottomGas = PlannerGas("21", "35")
    val decoGases = mutableStateListOf(PlannerGas("50", "0"))
    var bottomPpO2 by mutableStateOf("1.4")
    var decoPpO2 by mutableStateOf("1.6")
    var gfLow by mutableStateOf("30")
    var gfHigh by mutableStateOf("75")
    var descentRate by mutableStateOf("18")
    var ascentRate by mutableStateOf("9")
    var lastAscentRate by mutableStateOf("9")
    var lastStopDeep by mutableStateOf(false)
    var saltWater by mutableStateOf(true)
    var surfaceMbar by mutableStateOf("1013")
    var bottomSac by mutableStateOf("20")
    var decoSac by mutableStateOf("17")
    var maxEnd by mutableStateOf("30")

    /** Show the fields in [system], converting what is typed. */
    fun useUnits(system: UnitSystem) {
        if (system == units) return
        val toFeet = system == UnitSystem.IMPERIAL
        fun length(text: String): String = parse(text)?.let { v ->
            val mm = if (toFeet) v * 1000 else v * MM_PER_FOOT
            trimmed(if (toFeet) mm / MM_PER_FOOT else mm / 1000)
        } ?: text
        fun sac(text: String): String = parse(text)?.let { v ->
            if (toFeet) twoDecimals(v * 1000 / ML_PER_CUFT) else trimmed(v * ML_PER_CUFT / 1000)
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

    fun depthMm(text: String): Int? = parse(text)?.takeIf { it > 0 }
        ?.let { round(if (metric) it * 1000 else it * MM_PER_FOOT).toInt() }
        ?.takeIf { it <= 300_000 }

    fun minutes(text: String): Int? = parse(text)?.takeIf { it > 0 && it <= 600 }?.let { round(it * 60).toInt() }

    fun rate(text: String): Int? = depthMm(text)?.takeIf { it >= 1000 }

    fun sacMl(text: String): Int? = parse(text)?.takeIf { it > 0 && it < 100 }
        ?.let { round(if (metric) it * 1000 else it * ML_PER_CUFT).toInt() }

    fun gf(text: String): Int? = parse(text)?.takeIf { it >= 10 && it <= 100 }?.let { round(it).toInt() }

    fun gfValid(): Boolean {
        val low = gf(gfLow) ?: return false
        val high = gf(gfHigh) ?: return false
        return low <= high
    }

    fun surface(): Int? = parse(surfaceMbar)?.takeIf { it >= 500 && it <= 1100 }?.let { round(it).toInt() }

    fun maxEndMm(): Int? = if (maxEnd.isBlank()) null else depthMm(maxEnd)

    /** The plan for the inputs, or null while some field is invalid. */
    fun plan(): DivePlan? {
        val levels = levels.map { l ->
            PlanLevel(depthMm(l.depth) ?: return null, minutes(l.minutes) ?: return null)
        }
        val bottom = bottomGas.parsed() ?: return null
        val deco = decoGases.map { it.parsed() ?: return null }
        if (!gfValid()) return null
        val ascent = rate(ascentRate) ?: return null
        val last = rate(lastAscentRate) ?: return null
        if (maxEnd.isNotBlank() && maxEndMm() == null) return null
        return DivePlan(
            levels = levels,
            bottomGas = bottom,
            decoGases = deco,
            gf = GradientFactors((gf(gfLow) ?: return null) / 100.0, (gf(gfHigh) ?: return null) / 100.0),
            bottomPpO2Mbar = round((parse(bottomPpO2) ?: return null) * 1000).toInt(),
            decoPpO2Mbar = round((parse(decoPpO2) ?: return null) * 1000).toInt(),
            descentRate = rate(descentRate) ?: return null,
            ascentRates = AscentRates(ascent, ascent, ascent, last),
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
    LaunchedEffect(unitSystem) { state.useUnits(unitSystem) }
    val metric = state.units == UnitSystem.METRIC
    val unit = if (metric) "m" else "ft"
    val input = state.plan()
    val result = remember(input) { input?.let { DecoPlanner.plan(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModelNote()

        PlannerCard("Bottom profile") {
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

        PlannerCard("Gases") {
            GasFields("Bottom", state.bottomGas, onRemove = null)
            state.decoGases.forEachIndexed { i, gas ->
                GasFields("Deco ${i + 1}", gas, onRemove = { state.decoGases.removeAt(i) })
            }
            TextButton(onClick = { state.decoGases.add(PlannerGas("100", "0")) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add deco gas")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Bottom pO2 (bar)", state.bottomPpO2, listOf("1.2", "1.3", "1.4", "1.5", "1.6"), Modifier.weight(1f)) { state.bottomPpO2 = it }
                Picker("Deco pO2 (bar)", state.decoPpO2, listOf("1.4", "1.5", "1.6"), Modifier.weight(1f)) { state.decoPpO2 = it }
            }
            Hint("Deco gases are switched to at the stop where they reach the deco pO2.")
        }

        PlannerCard("Settings") {
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
                Picker("Last stop", if (state.lastStopDeep) deep else shallow, listOf(shallow, deep), Modifier.weight(1f)) {
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
            result == null -> ErrorText("Fix the fields marked in red to see a plan.")
            result.error == PlanError.LEVEL_TOO_SHORT -> ErrorText("A level is shorter than the travel to it.")
            else -> {
                ResultCard(result, state.units)
                if (result.warnings.isNotEmpty()) WarningsCard(result.warnings, state.units)
                RuntimeTable(result, state.units)
            }
        }
    }
}

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
    PlannerCard("Plan", MaterialTheme.colorScheme.secondaryContainer) {
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
    PlannerCard("Warnings", MaterialTheme.colorScheme.errorContainer) {
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

/** One table line: consecutive ascent segments on one gas are merged. */
private class TableLine(val phase: String, val depthMm: Int, var durationS: Int, var runtimeS: Int, val gas: BreathingGas, val switched: Boolean)

private fun tableLines(plan: DecoPlan): List<TableLine> {
    val lines = mutableListOf<TableLine>()
    var gas: BreathingGas? = null
    for (s in plan.segments) {
        val phase = phaseOf(s)
        val last = lines.lastOrNull()
        if (phase == "Ascent" && last != null && last.phase == "Ascent" && last.gas == s.gas && !s.bottom) {
            lines[lines.lastIndex] = TableLine(last.phase, s.endDepthMm, last.durationS + s.durationS, s.endS, s.gas, last.switched)
        } else {
            lines += TableLine(phase, s.endDepthMm, s.durationS, s.endS, s.gas, gas != null && gas != s.gas)
        }
        gas = s.gas
    }
    return lines
}

private fun phaseOf(s: PlanSegment): String = when {
    s.endDepthMm > s.startDepthMm -> "Descent"
    s.endDepthMm < s.startDepthMm -> "Ascent"
    s.bottom -> "Level"
    else -> "Stop"
}

@Composable
private fun RuntimeTable(plan: DecoPlan, units: UnitSystem) {
    PlannerCard("Runtime table") {
        TableRow(header = true, cells = listOf("", "Depth", "Time", "Run", "Gas"))
        HorizontalDivider()
        tableLines(plan).forEach { line ->
            TableRow(
                header = false,
                cells = listOf(line.phase, depth(line.depthMm, units), Format.duration(line.durationS), minutes(line.runtimeS), line.gas.name),
                bold = line.phase == "Stop",
                gasSwitched = line.switched,
            )
        }
        Hint("Time is the segment's length, Run the runtime at its end. Stops end on whole minutes.")
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
private fun PlannerCard(title: String, container: Color? = null, content: @Composable () -> Unit) {
    val colors = container?.let { CardDefaults.elevatedCardColors(containerColor = it) } ?: CardDefaults.elevatedCardColors()
    ElevatedCard(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A fixed choice; a read-only field that opens a menu. */
@Composable
private fun Picker(label: String, value: String, options: List<String>, modifier: Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value,
            {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The field swallows taps for focus; this layer opens the menu instead.
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { v -> DropdownMenuItem(text = { Text(v) }, onClick = { onChange(v); open = false }) }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun DecimalField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, isError: Boolean = false) {
    OutlinedTextField(
        value,
        { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error)

private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** Whole metres or feet: stops sit on whole units. */
private fun depth(mm: Int, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) "${round(mm / 1000.0).toInt()} m" else "${round(mm / MM_PER_FOOT).toInt()} ft"

private fun volume(litres: Double, units: UnitSystem): String =
    if (units == UnitSystem.METRIC) "${round(litres).toInt()} L" else "${trimmed(litres * 1000 / ML_PER_CUFT)} cuft"

/** Runtime in minutes, rounded up as dive tables show it. */
private fun minutes(seconds: Int): String = "${(seconds + 59) / 60} min"

private fun trimmed(v: Double): String = Format.oneDecimal(v).removeSuffix(".0")

private fun twoDecimals(v: Double): String {
    val hundredths = round(v * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
