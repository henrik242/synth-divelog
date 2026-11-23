package no.synth.divelog.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.format.Format
import kotlin.math.abs
import kotlin.math.round

/** A number typed with either decimal separator, or null when it is not one. */
fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** One decimal, dropped when it is zero: 33.2, 30. */
fun trimmedDecimal(v: Double): String = Format.oneDecimal(v).removeSuffix(".0")

/** Two decimals: 1.62. */
fun twoDecimals(v: Double): String {
    val hundredths = round(v * 100).toLong()
    val sign = if (hundredths < 0) "-" else ""
    val units = abs(hundredths)
    return "$sign${units / 100}.${(units % 100).toString().padStart(2, '0')}"
}

/**
 * A text field that takes digits and a decimal separator only, and a leading minus when
 * [signed]. A signed field gets the full keyboard: the iOS decimal pad has no minus key.
 */
@Composable
fun DecimalField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    signed: Boolean = false,
) {
    OutlinedTextField(
        value,
        { text ->
            val minus = if (signed && text.trimStart().startsWith('-')) "-" else ""
            onChange(minus + text.filter { c -> c.isDigit() || c == '.' || c == ',' })
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * A full-width card with a title, as the calculators group their inputs and results; [action]
 * sits at the right of the title.
 */
@Composable
fun ToolCard(
    title: String,
    container: Color? = null,
    icon: ImageVector? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = container?.let { CardDefaults.elevatedCardColors(containerColor = it) } ?: CardDefaults.elevatedCardColors()
    ElevatedCard(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon == null && action == null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icon?.let { Icon(it, contentDescription = null) }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    action?.invoke(this)
                }
            }
            content()
        }
    }
}

/**
 * A [ToolCard] whose content folds away: the title row toggles it, and [summary] stands in for
 * the content while folded, in the error colour when [summaryIsError].
 */
@Composable
fun CollapsibleToolCard(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    summary: String,
    summaryIsError: Boolean = false,
    content: @Composable () -> Unit,
) = CollapsibleToolCard(
    title,
    expanded,
    onExpandedChange,
    collapsed = {
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = if (summaryIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
    content = content,
)

/** A [ToolCard] whose title folds it: [content] when open, [collapsed] when folded. */
@Composable
fun CollapsibleToolCard(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    collapsed: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) content() else collapsed()
        }
    }
}

/** A checkbox with its label; the whole row toggles. */
@Composable
fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/**
 * A read-only field that acts as a button: [value] under [label], [onClick] on a tap. An empty
 * [value] shows the label alone, as a prompt. Screen readers get one button reading the label
 * and value.
 */
@Composable
fun TapField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Box(modifier) {
        OutlinedTextField(
            value,
            {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = trailingIcon?.let { { Icon(it, contentDescription = null) } },
            singleLine = true,
            isError = isError,
            // The layer below stands for the field: no focus stop or edit box of its own.
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false }.clearAndSetSemantics {},
        )
        // The field swallows taps for focus; this layer takes them instead.
        Box(
            Modifier.matchParentSize()
                .semantics {
                    contentDescription = if (value.isEmpty()) label else "$label: $value"
                    if (isError) error("Not valid")
                }
                .clickable(role = Role.Button, onClick = onClick),
        )
    }
}

/** A fixed choice; a read-only field that opens a menu. */
@Composable
fun OptionPicker(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TapField(label, value, Modifier.fillMaxWidth(), trailingIcon = Icons.Filled.ArrowDropDown) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { v -> DropdownMenuItem(text = { Text(v) }, onClick = { onChange(v); open = false }) }
        }
    }
}
