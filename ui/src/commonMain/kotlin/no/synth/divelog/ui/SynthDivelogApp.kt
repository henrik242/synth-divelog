package no.synth.divelog.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
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
import no.synth.divelog.ui.buddies.BuddiesSection
import no.synth.divelog.ui.dive.DiveDetailScreen
import no.synth.divelog.ui.dive.DiveEditScreen
import no.synth.divelog.ui.dive.DiveRow
import no.synth.divelog.ui.settings.SettingsSection
import no.synth.divelog.ui.sites.SitesSection
import no.synth.divelog.ui.stats.StatisticsSection

private enum class Section(val label: String) {
    DIVES("Dives"), SITES("Sites"), BUDDIES("Buddies"), STATS("Stats"), SETTINGS("Settings")
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
                Section.SITES -> SitesSection(container, unitSystem, dataVersion)
                Section.BUDDIES -> BuddiesSection(container, unitSystem, dataVersion)
                Section.STATS -> StatisticsSection(container, unitSystem, dataVersion)
                Section.SETTINGS -> SettingsSection(container, unitSystem, onUnitSystemChange)
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
    var openDiveId by remember(dataVersion) { mutableStateOf<Long?>(null) }
    var editing by remember(dataVersion) { mutableStateOf(false) }
    var reloadKey by remember(dataVersion) { mutableStateOf(0) }

    val currentDive = openDiveId
    if (currentDive != null) {
        if (editing) {
            DiveEditScreen(
                container = container,
                diveId = currentDive,
                unitSystem = unitSystem,
                onDone = { editing = false; reloadKey++ },
                onCancel = { editing = false },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(onClick = { openDiveId = null }) { Text("< Dives") }
                }
                DiveDetailScreen(
                    container = container,
                    diveId = currentDive,
                    unitSystem = unitSystem,
                    reloadKey = reloadKey,
                    onEdit = { editing = true },
                    onChanged = { reloadKey++ },
                    onDeleted = { openDiveId = null; reloadKey++ },
                )
            }
        }
        return
    }

    val dives = remember(dataVersion, reloadKey) { container.dives.allDives() }
    val siteNames = remember(dataVersion, reloadKey) { container.sites.allSites().associate { it.id to it.name } }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DiveSort.DATE) }

    val shown = remember(dives, query, sort, siteNames) {
        val q = query.trim().lowercase()
        dives
            .filter { d ->
                if (q.isEmpty()) true
                else {
                    val site = d.siteId?.let { siteNames[it] } ?: ""
                    d.number?.toString()?.contains(q) == true ||
                        site.lowercase().contains(q) ||
                        (d.notes?.lowercase()?.contains(q) == true)
                }
            }
            .sortedWith(
                when (sort) {
                    DiveSort.DATE -> compareByDescending { it.startEpochSeconds }
                    DiveSort.NUMBER -> compareByDescending { it.number ?: 0 }
                    DiveSort.DEPTH -> compareByDescending { it.maxDepthMm ?: 0 }
                },
            )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDownloadClick) { Text("Download") }
        }
        if (dives.isEmpty()) {
            EmptyState("No dives yet. Tap Download to pull dives from your computer.")
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiveSort.entries.forEach { s ->
                    FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { dive ->
                    DiveRow(
                        dive = dive,
                        unitSystem = unitSystem,
                        onClick = { openDiveId = dive.id },
                        siteName = dive.siteId?.let { siteNames[it] },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private enum class DiveSort(val label: String) { DATE("Date"), NUMBER("Number"), DEPTH("Depth") }

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
    }
}
