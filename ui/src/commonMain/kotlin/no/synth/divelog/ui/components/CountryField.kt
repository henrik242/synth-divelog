package no.synth.divelog.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/**
 * Country input with a substring-filtered dropdown of [COUNTRY_NAMES]. Free text is still
 * accepted so countries already stored in a logbook (or not on the list) keep working;
 * Enter or Done takes the first suggestion, and the X button clears the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val matches = matchCountries(value)
    // An exact match needs no further suggestions.
    val showMenu = expanded && matches.isNotEmpty() && !(matches.size == 1 && matches[0].equals(value.trim(), ignoreCase = true))

    fun pick(name: String) {
        onValueChange(name)
        expanded = false
    }

    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text("Country") },
            singleLine = true,
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange(""); expanded = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear country")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                // Exact (case-insensitive) match is canonicalised, otherwise take the top suggestion.
                val exact = COUNTRY_NAMES.firstOrNull { it.equals(value.trim(), ignoreCase = true) }
                (exact ?: matches.firstOrNull())?.let { pick(it) } ?: run { expanded = false }
            }),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            matches.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { pick(name) })
            }
        }
    }
}
