package no.synth.divelog.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.DepthLimits
import no.synth.divelog.core.gas.DepthUnit
import no.synth.divelog.core.gas.MIN_PPO2
import no.synth.divelog.core.gas.Water
import no.synth.divelog.core.gas.WaterColumn
import no.synth.divelog.core.model.units.FEET_PER_METRE
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.components.CheckRow
import no.synth.divelog.ui.components.DecimalField
import no.synth.divelog.ui.components.OptionPicker
import no.synth.divelog.ui.components.ToolCard
import no.synth.divelog.ui.components.parseDecimal
import no.synth.divelog.ui.components.trimmedDecimal
import no.synth.divelog.ui.components.twoDecimals
import kotlin.math.round

/** MOD/END inputs, as typed; [maxEnd] is in [units]. Held above the screen so they survive leaving the tab. */
@Stable
class ModEndState {
    var units by mutableStateOf(UnitSystem.METRIC)
        private set
    var o2 by mutableStateOf("32")
    var he by mutableStateOf("0")
    var maxPpO2 by mutableStateOf("1.4")
    var maxEnd by mutableStateOf("30")
    var saltWater by mutableStateOf(true)
    var surfaceMbar by mutableStateOf("1013")
    var o2Narcotic by mutableStateOf(true)

    /** Show the END limit in [system], converting what is typed. */
    fun useUnits(system: UnitSystem) {
        if (system == units) return
        parseDecimal(maxEnd)?.let { d ->
            maxEnd = trimmedDecimal(if (system == UnitSystem.IMPERIAL) d * FEET_PER_METRE else d / FEET_PER_METRE)
        }
        units = system
    }

    fun gas(): BreathingGas? {
        val o2 = parseDecimal(o2) ?: return null
        val he = parseDecimal(he.ifBlank { "0" }) ?: return null
        return BreathingGas.ofPercent(o2, he)?.takeIf { it.o2 > 0 }
    }

    fun surface(): Int? = parseDecimal(surfaceMbar)?.takeIf { it in 500.0..1100.0 }?.let { round(it).toInt() }

    fun limits(): DepthLimits? {
        val surface = surface() ?: return null
        val unit = if (units == UnitSystem.METRIC) DepthUnit.METRES else DepthUnit.FEET
        return DepthLimits(WaterColumn(surface, if (saltWater) Water.SALT else Water.FRESH), unit)
    }
}

@Composable
fun ModEndScreen(state: ModEndState, unitSystem: UnitSystem) {
    // Convert before anything reads the fields, so the frame after a unit switch is already right.
    Snapshot.withoutReadObservation { state.useUnits(unitSystem) }
    val unit = if (state.units == UnitSystem.METRIC) "m" else "ft"
    val gas = state.gas()
    val maxPpO2 = parseDecimal(state.maxPpO2)?.takeIf { it > 0 }
    val maxEnd = parseDecimal(state.maxEnd)?.takeIf { it >= 0 }
    val limits = state.limits()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToolCard("Mix") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.o2, { state.o2 = it }, "O2 %", Modifier.weight(1f), isError = gas == null)
                DecimalField(state.he, { state.he = it }, "He %", Modifier.weight(1f), isError = gas == null)
            }
            if (gas == null) Text("O2 must be above 0 and O2 + He at most 100 %.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPicker("Max pO2 (bar)", state.maxPpO2, listOf("1.2", "1.3", "1.4", "1.5", "1.6"), Modifier.weight(1f)) {
                    state.maxPpO2 = it
                }
                DecimalField(state.maxEnd, { state.maxEnd = it }, "Max END ($unit)", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DecimalField(state.surfaceMbar, { state.surfaceMbar = it }, "Surface (mbar)", Modifier.weight(1f), state.surface() == null)
                CheckRow("Salt water", state.saltWater, Modifier.weight(1f)) { state.saltWater = it }
            }
            CheckRow("O2 narcotic", state.o2Narcotic) { state.o2Narcotic = it }
            Text(
                if (state.o2Narcotic) {
                    "O2 and N2 both narcotic; only helium reduces narcosis."
                } else {
                    "Only N2 narcotic; for nitrox this is the equivalent air depth (EAD)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (gas != null && maxPpO2 != null && maxEnd != null && limits != null) {
            LimitsCard(gas, maxPpO2, maxEnd, unit, limits, state.o2Narcotic)
        }
    }
}

@Composable
private fun LimitsCard(gas: BreathingGas, maxPpO2: Double, maxEnd: Double, unit: String, limits: DepthLimits, o2Narcotic: Boolean) {
    val mod = limits.mod(gas, maxPpO2)
    val endDepth = limits.depthForEnd(gas, maxEnd, o2Narcotic)
    val minDepth = limits.minimumDepth(gas)
    val oxygenLimits = endDepth == null || mod <= endDepth
    val usable = if (endDepth == null) mod else minOf(mod, endDepth)
    ToolCard("Depth limits", MaterialTheme.colorScheme.secondaryContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LimitTile("MOD", "${trimmedDecimal(mod)} $unit", "Where pO2 reaches ${trimmedDecimal(maxPpO2)} bar", oxygenLimits, Modifier.weight(1f))
            LimitTile(
                "END limit",
                endDepth?.let { "${trimmedDecimal(it)} $unit" } ?: "No limit",
                if (endDepth == null) "Nothing narcotic" else "Where END reaches ${trimmedDecimal(maxEnd)} $unit",
                !oxygenLimits,
                Modifier.weight(1f),
            )
        }
        Text(
            "Usable to ${trimmedDecimal(usable)} $unit, limited by ${if (oxygenLimits) "oxygen" else "narcosis"}.",
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "MOD " + listOf(1.4, 1.6).joinToString(", ") { "at $it: ${trimmedDecimal(limits.mod(gas, it))} $unit" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (minDepth > 0) {
            Status(
                Icons.Filled.Warning,
                "Hypoxic at the surface: breathable from ${trimmedDecimal(minDepth)} $unit (pO2 ${twoDecimals(MIN_PPO2)}).",
            )
        }
    }
}

/** One depth limit; [limiting] marks the shallower one, the depth the mix can actually go to. */
@Composable
private fun LimitTile(label: String, value: String, basis: String, limiting: Boolean, modifier: Modifier) {
    val container = if (limiting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ElevatedCard(modifier, colors = CardDefaults.elevatedCardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Status(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null)
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}
