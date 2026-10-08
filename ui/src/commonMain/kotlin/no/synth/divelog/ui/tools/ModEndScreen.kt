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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.gas.DepthLimits
import no.synth.divelog.core.gas.MIN_PPO2
import no.synth.divelog.core.gas.WaterScale
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.format.Format

private const val FEET_PER_METRE = 3.28084

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
    var o2Narcotic by mutableStateOf(true)

    /** Show the END limit in [system], converting what is typed. */
    fun useUnits(system: UnitSystem) {
        if (system == units) return
        parse(maxEnd)?.let { d ->
            val converted = if (system == UnitSystem.IMPERIAL) d * FEET_PER_METRE else d / FEET_PER_METRE
            maxEnd = Format.oneDecimal(converted).removeSuffix(".0")
        }
        units = system
    }

    val scale: WaterScale
        get() = when {
            units == UnitSystem.METRIC && saltWater -> WaterScale.METRES_SALT
            units == UnitSystem.METRIC -> WaterScale.METRES_FRESH
            saltWater -> WaterScale.FEET_SALT
            else -> WaterScale.FEET_FRESH
        }
}

@Composable
fun ModEndScreen(state: ModEndState, unitSystem: UnitSystem) {
    LaunchedEffect(unitSystem) { state.useUnits(unitSystem) }
    val unit = if (state.units == UnitSystem.METRIC) "m" else "ft"
    val o2 = parse(state.o2)
    val he = parse(state.he) ?: 0.0
    val maxPpO2 = parse(state.maxPpO2)?.takeIf { it > 0 }
    val maxEnd = parse(state.maxEnd)?.takeIf { it >= 0 }
    val mixValid = o2 != null && o2 > 0 && he >= 0 && o2 + he <= 100

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModCard("Mix") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(state.o2, { state.o2 = it }, "O2 %", Modifier.weight(1f), isError = !mixValid)
                DecimalField(state.he, { state.he = it }, "He %", Modifier.weight(1f), isError = !mixValid)
            }
            if (!mixValid) Text("O2 must be above 0 and O2 + He at most 100 %.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpO2Picker(state.maxPpO2, { state.maxPpO2 = it }, Modifier.weight(1f))
                DecimalField(state.maxEnd, { state.maxEnd = it }, "Max END ($unit)", Modifier.weight(1f))
            }
            Row {
                CheckRow("Salt water", state.saltWater, Modifier.weight(1f)) { state.saltWater = it }
                CheckRow("O2 narcotic", state.o2Narcotic, Modifier.weight(1f)) { state.o2Narcotic = it }
            }
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

        if (mixValid && maxPpO2 != null && maxEnd != null) {
            LimitsCard(o2, he, maxPpO2, maxEnd, unit, state)
        }
    }
}

@Composable
private fun LimitsCard(o2: Double, he: Double, maxPpO2: Double, maxEnd: Double, unit: String, state: ModEndState) {
    val mod = DepthLimits.mod(o2, maxPpO2, state.scale)
    val endDepth = DepthLimits.depthForEnd(o2, he, maxEnd, state.scale, state.o2Narcotic)
    val minDepth = DepthLimits.minimumDepth(o2, state.scale)
    val oxygenLimits = endDepth == null || mod <= endDepth
    val usable = if (endDepth == null) mod else minOf(mod, endDepth)
    ModCard("Depth limits", MaterialTheme.colorScheme.secondaryContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LimitTile("MOD", "${trimmed(mod)} $unit", "Where pO2 reaches ${trimmed(maxPpO2)} bar", oxygenLimits, Modifier.weight(1f))
            LimitTile(
                "END limit",
                endDepth?.let { "${trimmed(it)} $unit" } ?: "No limit",
                if (endDepth == null) "Nothing narcotic" else "Where END reaches ${trimmed(maxEnd)} $unit",
                !oxygenLimits,
                Modifier.weight(1f),
            )
        }
        Text(
            "Usable to ${trimmed(usable)} $unit, limited by ${if (oxygenLimits) "oxygen" else "narcosis"}.",
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "MOD " + listOf(1.4, 1.6).joinToString(", ") { "at $it: ${trimmed(DepthLimits.mod(o2, it, state.scale))} $unit" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (minDepth > 0) {
            Status(
                Icons.Filled.Warning,
                "Hypoxic at the surface: breathable from ${trimmed(minDepth)} $unit (pO2 ${trimmed(MIN_PPO2)}).",
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

@Composable
private fun ModCard(title: String, container: Color? = null, content: @Composable () -> Unit) {
    val colors = container?.let { CardDefaults.elevatedCardColors(containerColor = it) } ?: CardDefaults.elevatedCardColors()
    ElevatedCard(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Max pO2 from the common limits; a read-only field that opens a menu. */
@Composable
private fun PpO2Picker(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value,
            {},
            readOnly = true,
            label = { Text("Max pO2 (bar)") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The field swallows taps for focus; this layer opens the menu instead.
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("1.2", "1.3", "1.4", "1.5", "1.6").forEach { v ->
                DropdownMenuItem(text = { Text(v) }, onClick = { onChange(v); open = false })
            }
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

private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** One decimal, dropped when it is zero. */
private fun trimmed(v: Double): String = Format.oneDecimal(v).removeSuffix(".0")

private fun twoDecimals(v: Double): String {
    val hundredths = kotlin.math.round(v * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
