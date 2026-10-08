package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Tank
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.core.model.units.Units
import kotlin.math.roundToInt

/**
 * A tank as typed in the editor. [id] is null until it is first saved. Pressures are in
 * the user's units (bar or psi), size as [Units.tankSize] shows it. [saved] is the tank as
 * loaded: its working pressure is not edited, only carried, and a field left as shown
 * keeps its stored value rather than the rounded text.
 */
internal data class TankDraft(
    val id: Long?,
    val o2: String,
    val he: String,
    val size: String,
    val start: String,
    val end: String,
    val saved: Tank? = null,
) {
    val workingPressureMbar: Int? get() = saved?.workingPressureMbar

    /** The unit [size] is in: litres, or nominal cuft in imperial when the working pressure is known. */
    fun sizeUnit(system: UnitSystem): String = Units.tankSize(0, workingPressureMbar, system).unit

    /** Whether a mix has been typed at all; an empty one is no gas, not an error. */
    fun hasMix(): Boolean = o2.isNotBlank() || he.isNotBlank()

    /** O2 and He permille, or null when there is no mix or it is not a valid one. */
    fun gas(): Pair<Int, Int>? {
        val o2 = parse(o2) ?: return null
        val he = parse(he) ?: 0.0
        if (o2 <= 0 || he < 0 || o2 + he > 100) return null
        return (o2 * 10).roundToInt() to (he * 10).roundToInt()
    }

    fun volumeMl(system: UnitSystem): Int? {
        saved?.volumeMl?.let { if (size == sizeText(it, workingPressureMbar, system)) return it }
        val v = parse(size)?.takeIf { it > 0 } ?: return null
        return Units.tankVolumeMl(v, workingPressureMbar, system)
    }

    fun startMbar(system: UnitSystem): Int? = toMbar(start, saved?.startPressureMbar, system)

    fun endMbar(system: UnitSystem): Int? = toMbar(end, saved?.endPressureMbar, system)

    companion object {
        fun of(tank: Tank, o2Permille: Int?, hePermille: Int?, system: UnitSystem) = TankDraft(
            id = tank.id,
            o2 = o2Permille?.let { trimmed(it / 10.0) } ?: "",
            he = hePermille?.takeIf { it > 0 }?.let { trimmed(it / 10.0) } ?: "",
            size = tank.volumeMl?.let { sizeText(it, tank.workingPressureMbar, system) } ?: "",
            start = tank.startPressureMbar?.let { fromMbar(it, system) } ?: "",
            end = tank.endPressureMbar?.let { fromMbar(it, system) } ?: "",
            saved = tank,
        )

        fun blank() = TankDraft(id = null, o2 = "", he = "", size = "", start = "", end = "")

        private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

        private fun trimmed(v: Double) = Format.oneDecimal(v).removeSuffix(".0")

        private fun sizeText(ml: Int, workingPressureMbar: Int?, system: UnitSystem) =
            trimmed(Units.tankSize(ml, workingPressureMbar, system).value)

        private fun fromMbar(mbar: Int, system: UnitSystem) =
            Units.pressure(mbar, system).value.roundToInt().toString()

        private fun toMbar(text: String, saved: Int?, system: UnitSystem): Int? {
            if (saved != null && text == fromMbar(saved, system)) return saved
            val v = parse(text)?.takeIf { it >= 0 } ?: return null
            // One unit of the system in millibar: 1 bar, or 1 psi.
            val mbarPerUnit = 1_000.0 / Units.pressure(1_000, system).value
            return (v * mbarPerUnit).roundToInt()
        }
    }
}

/** One row per tank: gas, size and pressures, each change handed to [onChange]. */
@Composable
internal fun TanksEditor(
    drafts: List<TankDraft>,
    unitSystem: UnitSystem,
    onChange: (index: Int, TankDraft) -> Unit,
    onRemove: (index: Int) -> Unit,
    onAdd: () -> Unit,
) {
    val pressureUnit = Units.pressure(0, unitSystem).unit
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gases", style = MaterialTheme.typography.titleSmall)
        drafts.forEachIndexed { i, d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val invalid = d.hasMix() && d.gas() == null
                    Text(
                        d.gas()?.let { (o2, he) -> Format.gasName(o2, he) } ?: if (invalid) "Invalid mix" else "",
                        Modifier.weight(1f),
                        color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = { onRemove(i) }) { Icon(Icons.Filled.Close, contentDescription = "Remove gas") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val invalid = d.hasMix() && d.gas() == null
                    Field(d.o2, "O2 %", Modifier.weight(1f), isError = invalid) { onChange(i, d.copy(o2 = it)) }
                    Field(d.he, "He %", Modifier.weight(1f), isError = invalid) { onChange(i, d.copy(he = it)) }
                    Field(d.size, "Size (${d.sizeUnit(unitSystem)})", Modifier.weight(1f)) { onChange(i, d.copy(size = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(d.start, "Start ($pressureUnit)", Modifier.weight(1f)) { onChange(i, d.copy(start = it)) }
                    Field(d.end, "End ($pressureUnit)", Modifier.weight(1f)) { onChange(i, d.copy(end = it)) }
                }
            }
        }
        TextButton(onClick = onAdd) { Text("Add gas") }
    }
}

@Composable
private fun Field(value: String, label: String, modifier: Modifier, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}
