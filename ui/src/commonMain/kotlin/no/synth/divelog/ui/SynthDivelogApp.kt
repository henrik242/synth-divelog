package no.synth.divelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.format.Format

private enum class Section(val label: String) {
    DIVES("Dives"), SITES("Sites"), BUDDIES("Buddies"), SETTINGS("Settings")
}

/**
 * Root of the shared app. Reads from [container]; [onDownloadClick] launches the
 * platform download flow, and [dataVersion] is bumped by the host to trigger a
 * reload after an import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthDivelogApp(
    container: AppContainer,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onDownloadClick: () -> Unit,
    dataVersion: Int = 0,
) {
    var section by remember { mutableStateOf(Section.DIVES) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(section.label) }) },
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { s ->
                    NavigationBarItem(
                        selected = section == s,
                        onClick = { section = s },
                        icon = {},
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (section) {
                Section.DIVES -> DivesSection(container, unitSystem, onDownloadClick, dataVersion)
                Section.SITES -> SitesSection(container, dataVersion)
                Section.BUDDIES -> BuddiesSection(container, dataVersion)
                Section.SETTINGS -> SettingsSection(unitSystem, onUnitSystemChange)
            }
        }
    }
}

@Composable
private fun DivesSection(
    container: AppContainer,
    unitSystem: UnitSystem,
    onDownloadClick: () -> Unit,
    dataVersion: Int,
) {
    val dives = remember(dataVersion) { container.dives.allDives() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDownloadClick) { Text("Download") }
        }
        if (dives.isEmpty()) {
            EmptyState("No dives yet. Tap Download to pull dives from your computer.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(dives) { dive ->
                    DiveRow(dive, unitSystem)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DiveRow(dive: Dive, unitSystem: UnitSystem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = dive.number?.let { "#$it" } ?: "Dive",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(
                text = Format.dateTime(dive.startEpochSeconds, dive.utcOffsetSeconds),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.depth(dive.maxDepthMm, unitSystem))
            Text(
                "${Format.duration(dive.durationSeconds)} min",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SitesSection(container: AppContainer, dataVersion: Int) {
    val sites = remember(dataVersion) { container.sites.allSites() }
    if (sites.isEmpty()) {
        EmptyState("No dive sites yet.")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(sites) { site: Site ->
                Text(site.name, Modifier.fillMaxWidth().padding(16.dp))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BuddiesSection(container: AppContainer, dataVersion: Int) {
    val buddies = remember(dataVersion) { container.buddies.all() }
    if (buddies.isEmpty()) {
        EmptyState("No buddies yet.")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(buddies) { buddy: Buddy ->
                Text(buddy.name, Modifier.fillMaxWidth().padding(16.dp))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SettingsSection(unitSystem: UnitSystem, onUnitSystemChange: (UnitSystem) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Units", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnitSystem.entries.forEach { system ->
                FilterChip(
                    selected = unitSystem == system,
                    onClick = { onUnitSystemChange(system) },
                    label = { Text(if (system == UnitSystem.METRIC) "Metric" else "Imperial") },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
    }
}
