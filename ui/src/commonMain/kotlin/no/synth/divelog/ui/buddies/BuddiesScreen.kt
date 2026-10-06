package no.synth.divelog.ui.buddies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import no.synth.divelog.ui.common.BackHandler
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.dive.DiveListWithDetail

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BuddiesSection(container: AppContainer, unitSystem: UnitSystem, dataVersion: Int) {
    var reloadKey by remember(dataVersion) { mutableStateOf(0) }
    var openBuddy by remember(dataVersion) { mutableStateOf<Buddy?>(null) }

    // Closing the detail re-reads the list so a rename or delete shows.
    fun close() {
        openBuddy = null
        reloadKey++
    }

    BackHandler(enabled = openBuddy != null) { close() }

    val current = openBuddy
    if (current != null) {
        var name by remember(current.id) { mutableStateOf(current.name) }
        var menuOpen by remember(current.id) { mutableStateOf(false) }
        val dives = remember(current.id) { container.buddies.divesForBuddy(current.id) }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { close() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                OutlinedTextField(
                    name,
                    { name = it; if (it.isNotBlank()) container.buddies.rename(current.id, it.trim()) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Buddy actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; container.buddies.delete(current.id); close() },
                        )
                    }
                }
            }
            Text("${dives.size} shared dive(s)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
            DiveListWithDetail(container, dives, unitSystem)
        }
        return
    }

    val buddies = remember(dataVersion, reloadKey) { container.buddies.all() }
    if (buddies.isEmpty()) {
        EmptyState("No buddies yet. Add them when editing a dive.", Icons.Outlined.Group)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(buddies) { buddy ->
            Text(buddy.name, Modifier.fillMaxWidth().clickable { openBuddy = buddy }.padding(16.dp))
            HorizontalDivider()
        }
    }
}
