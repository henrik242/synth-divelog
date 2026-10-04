package no.synth.divelog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.buddies.BuddiesSection
import no.synth.divelog.ui.dive.DiveDetailScreen
import no.synth.divelog.ui.dive.DiveEditScreen
import no.synth.divelog.ui.dive.DiveRow
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.format.Format
import no.synth.divelog.ui.settings.SettingsSection
import no.synth.divelog.ui.sites.SitesSection
import no.synth.divelog.ui.stats.StatisticsSection

private enum class Section(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    DIVES("Dives", Icons.Outlined.Waves, Icons.Filled.Waves),
    SITES("Sites", Icons.Outlined.Place, Icons.Filled.Place),
    BUDDIES("Buddies", Icons.Outlined.Group, Icons.Filled.Group),
    STATS("Stats", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * Root of the shared app. Reads from [container]; [onDownloadClick] launches the
 * platform download flow, and [dataVersion] is bumped by the host to trigger a
 * reload after an import.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SynthDivelogApp(
    container: AppContainer,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onDownloadClick: () -> Unit,
    onImport: () -> Unit = {},
    onExport: (formatId: String) -> Unit = {},
    onReparse: () -> Unit = {},
    cloudEnabled: Boolean = false,
    initialCloudUrl: String = "",
    initialCloudUsername: String = "",
    initialCloudPassword: String = "",
    onCloudConfigChange: (url: String, user: String, pass: String) -> Unit = { _, _, _ -> },
    onCloudPull: (url: String, user: String, pass: String) -> Unit = { _, _, _ -> },
    onCloudPush: (url: String, user: String, pass: String) -> Unit = { _, _, _ -> },
    onExit: () -> Unit = {},
    dataVersion: Int = 0,
) {
    var section by remember { mutableStateOf(Section.DIVES) }
    // Dive drill-in state lives here so the top bar can act as a back affordance
    // and the root back handler can clear it.
    var openDiveId by remember(dataVersion) { mutableStateOf<Long?>(null) }
    var editing by remember(dataVersion) { mutableStateOf(false) }
    val diveDrilledIn = section == Section.DIVES && openDiveId != null

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var backArmed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (diveDrilledIn) {
                        Row(
                            Modifier.clickable { editing = false; openDiveId = null },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to dive list")
                            Text(section.label, Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Text(section.label)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { s ->
                    NavigationBarItem(
                        selected = section == s,
                        onClick = { section = s },
                        icon = {
                            Icon(
                                imageVector = if (section == s) s.selectedIcon else s.icon,
                                contentDescription = s.label,
                            )
                        },
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Root-level back. Section drill-in handlers compose deeper and take
            // priority, so this only fires at a section root or a drilled-in dive.
            BackHandler {
                when {
                    diveDrilledIn -> { editing = false; openDiveId = null }
                    section != Section.DIVES -> section = Section.DIVES
                    backArmed -> onExit()
                    else -> {
                        backArmed = true
                        scope.launch { snackbarHostState.showSnackbar("Press back again to exit") }
                        scope.launch { delay(2000); backArmed = false }
                    }
                }
            }
            when (section) {
                Section.DIVES -> DivesSection(
                    container, unitSystem, onDownloadClick, dataVersion,
                    openDiveId = openDiveId,
                    onOpenDiveChange = { openDiveId = it },
                    editing = editing,
                    onEditingChange = { editing = it },
                )
                Section.SITES -> SitesSection(container, unitSystem, dataVersion)
                Section.BUDDIES -> BuddiesSection(container, unitSystem, dataVersion)
                Section.STATS -> StatisticsSection(container, unitSystem, dataVersion)
                Section.SETTINGS -> SettingsSection(
                    container = container,
                    unitSystem = unitSystem,
                    onUnitSystemChange = onUnitSystemChange,
                    onImport = onImport,
                    onExport = onExport,
                    onReparse = onReparse,
                    cloudEnabled = cloudEnabled,
                    initialCloudUrl = initialCloudUrl,
                    initialCloudUsername = initialCloudUsername,
                    initialCloudPassword = initialCloudPassword,
                    onCloudConfigChange = onCloudConfigChange,
                    onCloudPull = onCloudPull,
                    onCloudPush = onCloudPush,
                )
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
    openDiveId: Long?,
    onOpenDiveChange: (Long?) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
) {
    var reloadKey by remember(dataVersion) { mutableStateOf(0) }

    val currentDive = openDiveId
    if (currentDive != null) {
        if (editing) {
            DiveEditScreen(
                container = container,
                diveId = currentDive,
                unitSystem = unitSystem,
                onDone = { onEditingChange(false); reloadKey++ },
                onCancel = { onEditingChange(false) },
            )
        } else {
            DiveDetailScreen(
                container = container,
                diveId = currentDive,
                unitSystem = unitSystem,
                reloadKey = reloadKey,
                onEdit = { onEditingChange(true) },
                onChanged = { reloadKey++ },
                onDeleted = { onOpenDiveChange(null); reloadKey++ },
            )
        }
        return
    }

    val dives = remember(dataVersion, reloadKey) { container.dives.allDives() }
    val siteNames = remember(dataVersion, reloadKey) { container.sites.allSites().associate { it.id to it.name } }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DiveSort.DATE) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }

    val years = remember(dives) {
        dives.map { Format.year(it.startEpochSeconds, it.utcOffsetSeconds) }.distinct().sortedDescending()
    }

    val shown = remember(dives, query, sort, selectedYear, siteNames) {
        val q = query.trim().lowercase()
        dives
            .filter { selectedYear == null || Format.year(it.startEpochSeconds, it.utcOffsetSeconds) == selectedYear }
            .filter { d ->
                if (q.isEmpty()) true
                else {
                    val site = d.siteId?.let { siteNames[it] } ?: ""
                    d.number?.toString()?.contains(q) == true ||
                        site.lowercase().contains(q) ||
                        Format.date(d.startEpochSeconds, d.utcOffsetSeconds).lowercase().contains(q) ||
                        (d.notes?.lowercase()?.contains(q) == true)
                }
            }
            .sortedWith(
                when (sort) {
                    DiveSort.DATE -> compareByDescending { it.startEpochSeconds }
                    DiveSort.NUMBER -> compareByDescending { it.number ?: 0 }
                    DiveSort.DEPTH -> compareByDescending { it.maxDepthMm ?: 0 }
                    DiveSort.DURATION -> compareByDescending { it.durationSeconds }
                },
            )
    }
    // Group by month only for the date sort, where month headers make sense.
    val grouped = remember(shown, sort) {
        if (sort == DiveSort.DATE) {
            shown.groupBy { Format.monthYear(it.startEpochSeconds, it.utcOffsetSeconds) }
        } else {
            null
        }
    }

    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        if (dives.isEmpty()) {
            EmptyState("No dives yet. Tap Download to pull dives from your computer.", Icons.Outlined.Waves)
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(DiveSort.entries.toList()) { s ->
                    FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
                }
            }
            if (years.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedYear == null,
                            onClick = { selectedYear = null },
                            label = { Text("All") },
                        )
                    }
                    items(years) { y ->
                        FilterChip(
                            selected = selectedYear == y,
                            onClick = { selectedYear = if (selectedYear == y) null else y },
                            label = { Text("$y") },
                        )
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (grouped != null) {
                    grouped.forEach { (month, monthDives) ->
                        item {
                            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    month,
                                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(monthDives) { dive ->
                            DiveRow(dive, unitSystem, onClick = { onOpenDiveChange(dive.id) }, siteName = dive.siteId?.let { siteNames[it] })
                            HorizontalDivider()
                        }
                    }
                } else {
                    items(shown) { dive ->
                        DiveRow(dive, unitSystem, onClick = { onOpenDiveChange(dive.id) }, siteName = dive.siteId?.let { siteNames[it] })
                        HorizontalDivider()
                    }
                }
            }
        }
      }
      ExtendedFloatingActionButton(
          onClick = onDownloadClick,
          icon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
          text = { Text("Download") },
          modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
      )
    }
}

private enum class DiveSort(val label: String) { DATE("Date"), NUMBER("Number"), DEPTH("Depth"), DURATION("Duration") }
