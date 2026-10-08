package no.synth.divelog.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** A name with its stored id, e.g. a buddy or a tag. */
data class NamedItem(val id: Long, val name: String)

/**
 * The names on a dive (buddies, tags) as removable chips, and a field that suggests
 * existing names as you type. Enter adds the typed name: an existing one when it matches
 * exactly, otherwise a new one via [onAddNew].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NameChipsField(
    title: String,
    fieldLabel: String,
    selected: List<NamedItem>,
    all: List<NamedItem>,
    onAdd: (NamedItem) -> Unit,
    onAddNew: (String) -> Unit,
    onRemove: (NamedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val typed = query.trim()
    val selectedIds = selected.map { it.id }.toSet()
    val matches = remember(typed, all, selectedIds) {
        if (typed.isEmpty()) {
            emptyList()
        } else {
            all.filter { it.id !in selectedIds && it.name.contains(typed, ignoreCase = true) }
                .sortedWith(compareByDescending<NamedItem> { it.name.startsWith(typed, ignoreCase = true) }.thenBy { it.name })
                .take(MAX_MATCHES)
        }
    }
    val exact = all.firstOrNull { it.name.equals(typed, ignoreCase = true) }
    val offerNew = typed.isNotEmpty() && exact == null
    val showMenu = expanded && (matches.isNotEmpty() || offerNew)

    fun clear() {
        query = ""
        expanded = false
    }

    fun commit() {
        when {
            typed.isEmpty() -> {}
            exact != null -> if (exact.id !in selectedIds) onAdd(exact)
            else -> onAddNew(typed)
        }
        clear()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.forEach { item ->
                    InputChip(
                        selected = true,
                        onClick = { onRemove(item) },
                        label = { Text(item.name) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${item.name}", Modifier.size(InputChipDefaults.IconSize))
                        },
                    )
                }
            }
        }
        ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    expanded = true
                },
                label = { Text(fieldLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
                matches.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = { onAdd(item); clear() })
                }
                if (offerNew) {
                    DropdownMenuItem(text = { Text("Add “$typed”") }, onClick = { commit() })
                }
            }
        }
    }
}

private const val MAX_MATCHES = 8
