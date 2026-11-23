package no.synth.divelog.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.gas.Buoyancy
import no.synth.divelog.core.gas.ImperialTank
import no.synth.divelog.core.gas.MetricTank
import no.synth.divelog.core.gas.TANK_PRESETS
import no.synth.divelog.core.gas.TankMetal
import no.synth.divelog.core.gas.TankPreset
import no.synth.divelog.core.gas.TankSetup
import no.synth.divelog.core.gas.buoyancy
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.components.CheckRow
import no.synth.divelog.ui.components.DecimalField
import no.synth.divelog.ui.components.ToolCard
import no.synth.divelog.ui.components.parseDecimal
import no.synth.divelog.ui.components.trimmedDecimal

/** Tank calculator inputs, as typed, in [units]. Held above the screen so they survive leaving the tab. */
@Stable
class TankBuoyancyState {
    var units by mutableStateOf(UnitSystem.METRIC)
        private set

    // Metric: litres, bar, kg. Imperial: cuft, psi, lbs. Per cylinder.
    var size by mutableStateOf("12")
    var pressure by mutableStateOf("232")
    var weight by mutableStateOf("14.5")
    var aluminium by mutableStateOf(false)
    var saltWater by mutableStateOf(true)
    var valve by mutableStateOf(true)
    var doubles by mutableStateOf(true)
    var presetName by mutableStateOf<String?>("Twin 12 L 232 bar (14.5 kg each)")

    /** Show the fields in [system], converting what is typed. */
    fun useUnits(system: UnitSystem) {
        if (system == units) return
        when (system) {
            UnitSystem.IMPERIAL -> metric()?.toImperial()?.let { setImperial(it) }
            UnitSystem.METRIC -> imperial()?.toMetric()?.let { setMetric(it) }
        }
        units = system
    }

    fun choose(preset: TankPreset) {
        val metric = preset.metric
        val imperial = preset.imperial
        when (units) {
            UnitSystem.METRIC -> (metric ?: imperial?.toMetric())?.let { setMetric(it) }
            UnitSystem.IMPERIAL -> (imperial ?: metric?.toImperial())?.let { setImperial(it) }
        }
        aluminium = preset.metal == TankMetal.ALUMINIUM
        doubles = preset.doubles
        presetName = preset.name
    }

    fun result(): Buoyancy? {
        val setup = TankSetup(if (aluminium) TankMetal.ALUMINIUM else TankMetal.STEEL, saltWater, valve, doubles)
        return when (units) {
            UnitSystem.METRIC -> metric()?.let { buoyancy(it, setup) }
            UnitSystem.IMPERIAL -> imperial()?.let { buoyancy(it, setup) }
        }
    }

    private fun values(): Triple<Double, Double, Double>? {
        val a = parseDecimal(size)?.takeIf { it > 0 } ?: return null
        val b = parseDecimal(pressure)?.takeIf { it > 0 } ?: return null
        val c = parseDecimal(weight)?.takeIf { it > 0 } ?: return null
        return Triple(a, b, c)
    }

    private fun metric() = values()?.takeIf { units == UnitSystem.METRIC }?.let { (l, b, kg) -> MetricTank(l, b, kg) }

    private fun imperial() = values()?.takeIf { units == UnitSystem.IMPERIAL }?.let { (c, p, lbs) -> ImperialTank(c, p, lbs) }

    private fun setMetric(t: MetricTank) {
        size = trimmedDecimal(t.litres)
        pressure = trimmedDecimal(t.bar)
        weight = trimmedDecimal(t.kg)
    }

    private fun setImperial(t: ImperialTank) {
        size = trimmedDecimal(t.cubicFeet)
        pressure = kotlin.math.round(t.psi).toLong().toString()
        weight = trimmedDecimal(t.lbs)
    }
}

@Composable
fun TankBuoyancyScreen(state: TankBuoyancyState, unitSystem: UnitSystem) {
    // Convert before anything reads the fields, so the frame after a unit switch is already right.
    Snapshot.withoutReadObservation { state.useUnits(unitSystem) }
    val metric = state.units == UnitSystem.METRIC
    val result = state.result()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToolCard("Cylinder") {
            PresetPicker(state.presetName) { state.choose(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.size, { state.size = it; state.presetName = null }, if (metric) "Volume (L)" else "Capacity (cuft)", Modifier.weight(1f))
                DecimalField(state.pressure, { state.pressure = it; state.presetName = null }, if (metric) "Pressure (bar)" else "Pressure (psi)", Modifier.weight(1f))
            }
            DecimalField(
                state.weight,
                { state.weight = it; state.presetName = null },
                if (metric) "Weight per cylinder, no valve (kg)" else "Weight per cylinder, no valve (lbs)",
                Modifier.fillMaxWidth(),
            )
            val row = Modifier.fillMaxWidth()
            CheckRow("Salt water", state.saltWater, row) { state.saltWater = it }
            CheckRow("Doubles (two cylinders on a manifold)", state.doubles, row) { state.doubles = it; state.presetName = null }
            CheckRow("Aluminium", state.aluminium, row) { state.aluminium = it; state.presetName = null }
            CheckRow("Include valve", state.valve, row) { state.valve = it }
        }

        if (result == null) {
            Text("Enter the cylinder's size, pressure and weight.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ResultCard(result, metric)
            CalculationCard(result)
        }
    }
}

@Composable
private fun PresetPicker(selected: String?, onChoose: (TankPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected ?: "Choose a common cylinder", Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TANK_PRESETS.groupBy { it.group }.forEach { (group, presets) ->
                Text(
                    group,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                presets.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.name) }, onClick = { open = false; onChoose(preset) })
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: Buoyancy, metric: Boolean) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Buoyancy in the water", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BuoyancyTile("Full", result.fullKg, result.fullLbs, metric, "Includes the weight of the gas", Modifier.weight(1f))
                BuoyancyTile("Empty", result.emptyKg, result.emptyLbs, metric, "After the dive, gas used up", Modifier.weight(1f))
            }
            Text(
                "The gas in a full fill weighs ${weightLabel(result.emptyKg - result.fullKg, result.emptyLbs - result.fullLbs, metric)}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BuoyancyTile(label: String, kg: Double, lbs: Double, metric: Boolean, caption: String, modifier: Modifier) {
    val value = if (metric) kg else lbs
    val floats = value > 0
    ElevatedCard(modifier, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    if (floats) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = if (floats) "Floats" else "Sinks",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(signedWeight(value, if (metric) "kg" else "lbs"), style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                if (metric) signedWeight(lbs, "lbs") else signedWeight(kg, "kg"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(if (floats) "Floats" else if (value < 0) "Sinks" else "Neutral", fontWeight = FontWeight.SemiBold)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalculationCard(result: Buoyancy) {
    var open by remember { mutableStateOf(false) }
    ToolCard("How it is calculated") {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        if (open) {
            result.steps.forEachIndexed { i, step ->
                if (i > 0) HorizontalDivider()
                Text(
                    buildAnnotatedString {
                        append(step.text)
                        step.result?.let {
                            append(" = ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it) }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** "3.4 kg" in the chosen system, the other in brackets. */
private fun weightLabel(kg: Double, lbs: Double, metric: Boolean): String =
    if (metric) "${trimmedDecimal(kg)} kg (${trimmedDecimal(lbs)} lbs)" else "${trimmedDecimal(lbs)} lbs (${trimmedDecimal(kg)} kg)"

/** "+1.2 kg" / "-3.5 kg": buoyancy, so the sign says float or sink. */
private fun signedWeight(v: Double, unit: String) = (if (v > 0) "+" else "") + "${trimmedDecimal(v)} $unit"

