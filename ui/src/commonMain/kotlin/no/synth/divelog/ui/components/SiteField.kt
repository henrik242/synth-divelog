package no.synth.divelog.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

/** One selectable dive site: its [name] plus a [detail] line (place and country). */
data class SiteOption(val id: Long, val name: String, val detail: String)

/**
 * Site picker with a substring-filtered dropdown over [options], matching the name or the
 * place/country detail, so a long site list stays usable without a chip per site. The field
 * shows the selected site's name; the X button clears the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteField(
    options: List<SiteOption>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = options.firstOrNull { it.id == selectedId }
    // The query tracks what the user types; it resets to the selected site's name when the
    // selection changes from outside (remember keyed on selectedId).
    var query by remember(selectedId) { mutableStateOf(selected?.name ?: "") }
    var expanded by remember { mutableStateOf(false) }

    val matches = remember(query, options) {
        val q = query.trim()
        if (q.isEmpty()) {
            options.take(MAX_MATCHES)
        } else {
            options.filter { it.name.contains(q, true) || it.detail.contains(q, true) }
                .sortedByDescending { it.name.startsWith(q, true) }
                .take(MAX_MATCHES)
        }
    }
    val showMenu = expanded && matches.isNotEmpty()

    fun pick(option: SiteOption) {
        onSelect(option.id)
        query = option.name
        expanded = false
    }

    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
            },
            label = { Text("Site") },
            placeholder = { Text("Search sites") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty() || selectedId != null) {
                    IconButton(onClick = { query = ""; onSelect(null); expanded = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear site")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { matches.firstOrNull()?.let { pick(it) } ?: run { expanded = false } }),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            matches.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.name)
                            if (option.detail.isNotEmpty()) {
                                Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = { pick(option) },
                )
            }
        }
    }
}

private const val MAX_MATCHES = 8
