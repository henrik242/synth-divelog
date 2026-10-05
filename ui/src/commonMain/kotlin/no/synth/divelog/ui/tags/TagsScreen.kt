package no.synth.divelog.ui.tags

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
import androidx.compose.material.icons.outlined.Sell
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
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Tag
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.dive.DiveListWithDetail

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TagsSection(container: AppContainer, unitSystem: UnitSystem, dataVersion: Int) {
    var reloadKey by remember(dataVersion) { mutableStateOf(0) }
    var openTag by remember(dataVersion) { mutableStateOf<Tag?>(null) }

    // Closing the detail re-reads the list so a rename or delete shows.
    fun close() {
        openTag = null
        reloadKey++
    }

    BackHandler(enabled = openTag != null) { close() }

    val current = openTag
    if (current != null) {
        var name by remember(current.id) { mutableStateOf(current.name) }
        var menuOpen by remember(current.id) { mutableStateOf(false) }
        val dives = remember(current.id) { container.tags.divesForTag(current.id) }
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
                    { name = it; if (it.isNotBlank()) container.tags.rename(current.id, it.trim()) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Tag actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; container.tags.delete(current.id); close() },
                        )
                    }
                }
            }
            Text("${dives.size} tagged dive(s)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
            DiveListWithDetail(container, dives, unitSystem)
        }
        return
    }

    val tags = remember(dataVersion, reloadKey) { container.tags.all() }
    if (tags.isEmpty()) {
        EmptyState("No tags yet. Add them when editing a dive.", Icons.Outlined.Sell)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tags) { tag ->
            Text(tag.name, Modifier.fillMaxWidth().clickable { openTag = tag }.padding(16.dp))
            HorizontalDivider()
        }
    }
}
