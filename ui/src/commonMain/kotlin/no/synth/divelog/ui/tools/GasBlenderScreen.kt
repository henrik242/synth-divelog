package no.synth.divelog.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.gas.BlendPlan
import no.synth.divelog.core.gas.BlendProblem
import no.synth.divelog.core.gas.BlendStep
import no.synth.divelog.core.gas.Cylinder
import no.synth.divelog.core.gas.Fill
import no.synth.divelog.core.gas.Mix
import no.synth.divelog.core.gas.SourceGas
import no.synth.divelog.core.gas.TOLERANCE_BAR
import no.synth.divelog.core.gas.TOLERANCE_PERCENT
import no.synth.divelog.core.gas.planBlend
import no.synth.divelog.ui.format.Format
import kotlin.math.abs

/** One source gas row. Fixed rows ([fixedName] set) have a name and a mix that cannot be edited. */
@Stable
class GasRow(val fixedName: String?, o2: String, he: String, selected: Boolean) {
    var o2 by mutableStateOf(o2)
    var he by mutableStateOf(he)
    var selected by mutableStateOf(selected)

    /** The gas, or null while the mix is not a valid one. */
    fun toSourceGas(): SourceGas? {
        val o2 = parse(o2) ?: return null
        val he = parse(he) ?: return null
        if (o2 < 0 || he < 0 || o2 + he > 100 || o2 + he <= 0) return null
        return SourceGas(fixedName ?: mixName(o2, he), o2, he)
    }
}

/** Blender inputs, as typed. Held above the screen so they survive leaving the tab. */
@Stable
class GasBlenderState {
    var volume by mutableStateOf("12")
    var startBar by mutableStateOf("0")
    var startO2 by mutableStateOf("21")
    var startHe by mutableStateOf("0")
    var targetBar by mutableStateOf("220")
    var targetO2 by mutableStateOf("18")
    var targetHe by mutableStateOf("45")
    val gases = mutableStateListOf(
        GasRow("Air", "21", "0", selected = true),
        GasRow("O2", "100", "0", selected = true),
        GasRow("Helium", "0", "100", selected = true),
        GasRow(null, "32", "0", selected = false),
        GasRow(null, "10", "70", selected = false),
    )

    fun emptyCylinder() {
        startBar = "0"
        startO2 = "21"
        startHe = "0"
    }

    /** The plan for the current inputs, or null with the reason the inputs are incomplete. */
    fun plan(): Pair<BlendPlan?, String?> {
        val volume = parse(volume)?.takeIf { it > 0 } ?: return null to "Enter the cylinder volume."
        val startBar = parse(startBar)?.takeIf { it >= 0 } ?: return null to "Enter the starting pressure."
        val startO2 = parse(startO2) ?: return null to "Enter the starting O2."
        val startHe = parse(startHe) ?: return null to "Enter the starting He."
        if (startO2 < 0 || startHe < 0 || startO2 + startHe > 100) return null to "Starting O2 + He must be 0-100 %."
        val targetBar = parse(targetBar)?.takeIf { it > 0 } ?: return null to "Enter the target pressure."
        val targetO2 = parse(targetO2)?.takeIf { it >= 0 } ?: return null to "Enter the target O2."
        val targetHe = parse(targetHe)?.takeIf { it >= 0 } ?: return null to "Enter the target He."
        val gases = this.gases.filter { it.selected }.mapNotNull { it.toSourceGas() }.distinctBy { it.o2 to it.he }
        if (gases.isEmpty()) return null to "Select at least one gas to fill from."
        return planBlend(
            Cylinder(volume, startBar, startO2, startHe),
            Fill(targetO2, targetHe, targetBar),
            gases,
        ) to null
    }
}

@Composable
fun GasBlenderScreen(state: GasBlenderState) {
    val (plan, missing) = state.plan()
    val overLimit = plan?.problem == BlendProblem.MIX_OVER_100
    // The target card turns red while the target cannot be reached, so it shows next to the inputs.
    val targetTone = when {
        plan == null -> Tone.NEUTRAL
        plan.ok -> Tone.GOOD
        else -> Tone.BAD
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Starting gas") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(state.volume, { state.volume = it }, "Volume (L)", Modifier.weight(1f))
                NumberField(state.startBar, { state.startBar = it }, "Pressure (bar)", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(state.startO2, { state.startO2 = it }, "O2 %", Modifier.weight(1f))
                NumberField(state.startHe, { state.startHe = it }, "He %", Modifier.weight(1f))
            }
            TextButton(onClick = state::emptyCylinder) { Text("Empty cylinder") }
        }

        Section("Target", targetTone) {
            NumberField(state.targetBar, { state.targetBar = it }, "Pressure (bar)", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(state.targetO2, { state.targetO2 = it }, "O2 %", Modifier.weight(1f), isError = overLimit)
                NumberField(state.targetHe, { state.targetHe = it }, "He %", Modifier.weight(1f), isError = overLimit)
            }
            when (targetTone) {
                Tone.GOOD -> StatusLine(Icons.Filled.CheckCircle, "Possible with the selected gases")
                Tone.BAD -> StatusLine(
                    Icons.Filled.Warning,
                    if (overLimit) "O2 + He is over 100 %" else "Not possible with the selected gases",
                )
                Tone.NEUTRAL, Tone.STEPS -> {}
            }
        }

        Section("Fill from") {
            state.gases.forEachIndexed { index, row -> GasRowItem(row, onRemove = { state.gases.removeAt(index) }) }
            TextButton(onClick = { state.gases.add(GasRow(null, "", "", selected = false)) }) { Text("Add gas") }
        }

        when {
            missing != null -> Text(missing, color = MaterialTheme.colorScheme.onSurfaceVariant)
            plan != null && !overLimit -> PlanView(plan)
        }
    }
}

@Composable
private fun GasRowItem(row: GasRow, onRemove: () -> Unit) {
    val valid = row.toSourceGas() != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = row.selected && valid, onCheckedChange = { row.selected = it }, enabled = valid)
        if (row.fixedName != null) {
            Text("${row.fixedName} (${row.o2}/${row.he})", Modifier.weight(1f))
        } else {
            NumberField(row.o2, { row.o2 = it }, "O2 %", Modifier.weight(1f))
            Text("/", Modifier.padding(horizontal = 8.dp))
            NumberField(row.he, { row.he = it }, "He %", Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove gas") }
        }
    }
}

@Composable
private fun PlanView(plan: BlendPlan) {
    val r = plan.result
    val t = plan.target
    if (plan.ok) {
        Section("On target", Tone.GOOD, Icons.Filled.CheckCircle) {
            Text("${mixLabel(Mix(r.o2, r.he))} at ${Format.oneDecimal(r.pressureBar)} bar", style = MaterialTheme.typography.titleMedium)
            if (plan.litresUsed.isNotEmpty()) {
                Text("Gas used", style = MaterialTheme.typography.titleSmall)
                plan.litresUsed.forEach { (name, litres) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, Modifier.weight(1f))
                        Text("${Format.oneDecimal(litres)} L")
                    }
                }
            }
        }
    } else {
        Section("Not possible", Tone.BAD, Icons.Filled.Warning) {
            Text(
                "The selected gases cannot reach ${mixLabel(Mix(t.o2, t.he))} at ${Format.oneDecimal(t.pressureBar)} bar " +
                    "within 0.5 % O2/He and 1 bar. The closest they get is " +
                    "${mixLabel(Mix(r.o2, r.he))} at ${Format.oneDecimal(r.pressureBar)} bar:",
            )
            misses(plan).forEach { Text("- $it", fontWeight = FontWeight.SemiBold) }
            Text("Select or add other gases, or change the target.")
        }
    }

    // The recipe stands out on its own background; a failed plan's steps are only a reference,
    // so they stay neutral and dimmed and do not read as something to follow.
    Section(
        if (plan.ok) "Steps" else "Closest attempt",
        if (plan.ok) Tone.STEPS else Tone.NEUTRAL,
        if (plan.ok) Icons.Filled.FormatListNumbered else null,
    ) {
        if (plan.steps.isEmpty()) Text("Nothing to do: the cylinder already holds the target.")
        Column(Modifier.alpha(if (plan.ok) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            plan.steps.forEachIndexed { i, step -> StepItem(i + 1, step) }
        }
    }
}

/** What is outside tolerance, e.g. "O2 26.4 % (target 21 %)". */
private fun misses(plan: BlendPlan): List<String> {
    val r = plan.result
    val t = plan.target
    return buildList {
        if (abs(r.o2 - t.o2) > TOLERANCE_PERCENT) add("O2 ${trimmed(r.o2)} % (target ${trimmed(t.o2)} %)")
        if (abs(r.he - t.he) > TOLERANCE_PERCENT) add("He ${trimmed(r.he)} % (target ${trimmed(t.he)} %)")
        if (abs(r.pressureBar - t.pressureBar) > TOLERANCE_BAR) {
            add("${Format.oneDecimal(r.pressureBar)} bar (target ${Format.oneDecimal(t.pressureBar)} bar)")
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null)
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepItem(number: Int, step: BlendStep) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val (title, change) = when (step) {
                    is BlendStep.Drain -> (if (step.toBar <= 0) "Drain completely" else "Drain to ${Format.oneDecimal(step.toBar)} bar") to
                        "-${Format.oneDecimal(step.fromBar - step.toBar)} bar"
                    is BlendStep.Add -> (if (step.topUp) "Top up with ${step.gas.name}" else "Add ${step.gas.name}") to
                        "+${Format.oneDecimal(step.addedBar)} bar, ${Format.oneDecimal(step.litres)} L"
                }
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    "${Format.oneDecimal(step.fromBar)} → ${Format.oneDecimal(step.toBar)} bar ($change)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${mixLabel(step.mixBefore)} → ${mixLabel(step.mixAfter)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class Tone { NEUTRAL, GOOD, BAD, STEPS }

@Composable
private fun Section(title: String, tone: Tone = Tone.NEUTRAL, icon: ImageVector? = null, content: @Composable () -> Unit) {
    val colors = when (tone) {
        Tone.NEUTRAL -> CardDefaults.elevatedCardColors()
        Tone.GOOD -> CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        Tone.BAD -> CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        Tone.STEPS -> CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    ElevatedCard(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.let { Icon(it, contentDescription = null) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
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

private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** "Nitrox 32" for a helium-free nitrox, otherwise "O2/He". */
private fun mixName(o2: Double, he: Double): String =
    if (he == 0.0 && o2 > 21 && o2 < 41) "Nitrox ${trimmed(o2)}" else "${trimmed(o2)}/${trimmed(he)}"

private fun mixLabel(mix: Mix) = "${trimmed(mix.o2)}/${trimmed(mix.he)}"

/** One decimal, dropped when it is zero. */
private fun trimmed(v: Double): String = Format.oneDecimal(v).removeSuffix(".0")
