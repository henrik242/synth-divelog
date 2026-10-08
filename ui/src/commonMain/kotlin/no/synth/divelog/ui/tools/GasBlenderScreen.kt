package no.synth.divelog.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.gas.BlendPlan
import no.synth.divelog.core.gas.BlendStep
import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.Cylinder
import no.synth.divelog.core.gas.Fill
import no.synth.divelog.core.gas.SourceGas
import no.synth.divelog.core.gas.TOLERANCE_BAR
import no.synth.divelog.core.gas.TOLERANCE_PERMILLE
import no.synth.divelog.core.gas.planBlend
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.ui.components.DecimalField
import no.synth.divelog.ui.components.ToolCard
import no.synth.divelog.ui.components.parseDecimal
import no.synth.divelog.ui.components.trimmedDecimal
import kotlin.math.abs

/** One source gas row. Fixed rows ([fixedName] set) have a name and a mix that cannot be edited. */
@Stable
class GasRow(val fixedName: String?, o2: String, he: String, selected: Boolean) {
    var o2 by mutableStateOf(o2)
    var he by mutableStateOf(he)
    var selected by mutableStateOf(selected)

    /** The gas, or null while the mix is not a valid one. */
    fun toSourceGas(): SourceGas? {
        val o2 = parseDecimal(o2) ?: return null
        val he = parseDecimal(he) ?: return null
        val gas = BreathingGas.ofPercent(o2, he)?.takeIf { it.n2 < 1000 } ?: return null
        return SourceGas(fixedName ?: mixName(o2, he), gas)
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

    /** The target O2 and He are numbers but add up to more than 100 %. */
    fun targetOverLimit(): Boolean {
        val o2 = parseDecimal(targetO2) ?: return false
        val he = parseDecimal(targetHe) ?: return false
        return o2 >= 0 && he >= 0 && BreathingGas.ofPercent(o2, he) == null
    }

    /** The plan for the current inputs, or null with the reason the inputs are incomplete. */
    fun plan(): Pair<BlendPlan?, String?> {
        val volume = parseDecimal(volume)?.takeIf { it > 0 } ?: return null to "Enter the cylinder volume."
        val startBar = parseDecimal(startBar)?.takeIf { it >= 0 } ?: return null to "Enter the starting pressure."
        val startO2 = parseDecimal(startO2) ?: return null to "Enter the starting O2."
        val startHe = parseDecimal(startHe) ?: return null to "Enter the starting He."
        val startGas = BreathingGas.ofPercent(startO2, startHe) ?: return null to "Starting O2 + He must be 0-100 %."
        val targetBar = parseDecimal(targetBar)?.takeIf { it > 0 } ?: return null to "Enter the target pressure."
        val targetO2 = parseDecimal(targetO2)?.takeIf { it >= 0 } ?: return null to "Enter the target O2."
        val targetHe = parseDecimal(targetHe)?.takeIf { it >= 0 } ?: return null to "Enter the target He."
        val targetGas = BreathingGas.ofPercent(targetO2, targetHe) ?: return null to "Target O2 + He must be at most 100 %."
        val gases = this.gases.filter { it.selected }.mapNotNull { it.toSourceGas() }.distinctBy { it.gas }
        if (gases.isEmpty()) return null to "Select at least one gas to fill from."
        return planBlend(Cylinder(volume, startBar, startGas), Fill(targetGas, targetBar), gases) to null
    }
}

@Composable
fun GasBlenderScreen(state: GasBlenderState) {
    val (plan, missing) = state.plan()
    val overLimit = state.targetOverLimit()
    // The target card turns red while the target cannot be reached, so it shows next to the inputs.
    val targetTone = when {
        overLimit -> Tone.BAD
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
                DecimalField(state.volume, { state.volume = it }, "Volume (L)", Modifier.weight(1f))
                DecimalField(state.startBar, { state.startBar = it }, "Pressure (bar)", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DecimalField(state.startO2, { state.startO2 = it }, "O2 %", Modifier.weight(1f))
                DecimalField(state.startHe, { state.startHe = it }, "He %", Modifier.weight(1f))
            }
            TextButton(onClick = state::emptyCylinder) { Text("Empty cylinder") }
        }

        Section("Target", targetTone) {
            DecimalField(state.targetBar, { state.targetBar = it }, "Pressure (bar)", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.targetO2, { state.targetO2 = it }, "O2 %", Modifier.weight(1f), isError = overLimit)
                DecimalField(state.targetHe, { state.targetHe = it }, "He %", Modifier.weight(1f), isError = overLimit)
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
            plan != null -> PlanView(plan)
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
            DecimalField(row.o2, { row.o2 = it }, "O2 %", Modifier.weight(1f))
            Text("/", Modifier.padding(horizontal = 8.dp))
            DecimalField(row.he, { row.he = it }, "He %", Modifier.weight(1f))
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
            Text("${mixLabel(r.gas)} at ${Format.oneDecimal(r.pressureBar)} bar", style = MaterialTheme.typography.titleMedium)
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
                "The selected gases cannot reach ${mixLabel(t.gas)} at ${Format.oneDecimal(t.pressureBar)} bar " +
                    "within 0.5 % O2/He and 1 bar. The closest they get is " +
                    "${mixLabel(r.gas)} at ${Format.oneDecimal(r.pressureBar)} bar:",
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
        if (abs(r.gas.o2 - t.gas.o2) > TOLERANCE_PERMILLE) add("O2 ${percent(r.gas.o2)} % (target ${percent(t.gas.o2)} %)")
        if (abs(r.gas.he - t.gas.he) > TOLERANCE_PERMILLE) add("He ${percent(r.gas.he)} % (target ${percent(t.gas.he)} %)")
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
                    is BlendStep.Add -> (if (step.topUp) "Top up with ${step.source.name}" else "Add ${step.source.name}") to
                        "+${Format.oneDecimal(step.addedBar)} bar, ${Format.oneDecimal(step.litres)} L"
                }
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    "${Format.oneDecimal(step.fromBar)} → ${Format.oneDecimal(step.toBar)} bar ($change)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${mixLabel(step.gasBefore)} → ${mixLabel(step.gasAfter)}",
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
    val container = when (tone) {
        Tone.NEUTRAL -> null
        Tone.GOOD -> MaterialTheme.colorScheme.primaryContainer
        Tone.BAD -> MaterialTheme.colorScheme.errorContainer
        Tone.STEPS -> MaterialTheme.colorScheme.secondaryContainer
    }
    ToolCard(title, container, icon, content)
}

/** "Nitrox 32" for a helium-free nitrox, otherwise "O2/He". */
private fun mixName(o2: Double, he: Double): String =
    if (he == 0.0 && o2 > 21 && o2 < 41) "Nitrox ${trimmedDecimal(o2)}" else "${trimmedDecimal(o2)}/${trimmedDecimal(he)}"

private fun mixLabel(gas: BreathingGas) = "${percent(gas.o2)}/${percent(gas.he)}"

private fun percent(permille: Int): String = trimmedDecimal(permille / 10.0)
