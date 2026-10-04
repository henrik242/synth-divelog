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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.CompletableDeferred
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
import no.synth.divelog.ui.download.DiveComputerType
import no.synth.divelog.ui.download.DownloadController
import no.synth.divelog.ui.download.DownloadPickerDialog
import no.synth.divelog.ui.download.DownloadProgressDialog
import no.synth.divelog.ui.download.DownloadReviewDialog
import no.synth.divelog.ui.download.DownloadUiState
import no.synth.divelog.ui.download.NoSerialPorts
import no.synth.divelog.ui.download.SerialPortInfo
import no.synth.divelog.ui.download.SerialPorts
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
 * Root of the shared app. Reads from [container]. The Download button opens the shared
 * picker and runs the download over [serialPorts] (desktop jSerialComm, Android USB-serial
 * and paired Bluetooth Classic). [onPrepareDownload], if set, runs before the picker opens
 * so a platform can request its runtime permissions (Android: Bluetooth and notifications).
 * [onDownloadActive] brackets a running download so a platform can hold the process awake
 * (Android: a foreground service). [onDownloaded] lets the host refresh after a download;
 * [dataVersion] is bumped by the host to trigger a reload after an import.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SynthDivelogApp(
    container: AppContainer,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    serialPorts: SerialPorts = NoSerialPorts(),
    downloadTypes: List<DiveComputerType> = DiveComputerType.entries,
    onPrepareDownload: (suspend () -> Unit)? = null,
    onDownloadActive: (Boolean) -> Unit = {},
    onDownloaded: () -> Unit = {},
    onImport: () -> Unit = {},
    onExport: (formatId: String) -> Unit = {},
    onReparse: () -> Unit = {},
    cloudEnabled: Boolean = false,
    initialCloudEmail: String = "",
    initialCloudPassword: String = "",
    onCloudConfigChange: (email: String, pass: String) -> Unit = { _, _ -> },
    onCloudPull: (email: String, pass: String) -> Unit = { _, _ -> },
    onCloudPush: (email: String, pass: String) -> Unit = { _, _ -> },
    onExit: () -> Unit = {},
    statusMessage: String? = null,
    onStatusShown: () -> Unit = {},
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

    // Shared wired-serial download: picker, progress and orchestration over [serialPorts].
    val controller = remember(container, serialPorts) { DownloadController(container, serialPorts) }
    var downloadUi by remember { mutableStateOf<DownloadUiState>(DownloadUiState.Hidden) }
    var pickerPorts by remember { mutableStateOf<List<SerialPortInfo>>(emptyList()) }
    val cancelDownload = remember { mutableStateOf(false) }

    fun startSerialDownload(type: DiveComputerType, portId: String) {
        cancelDownload.value = false
        downloadUi = DownloadUiState.Running(0f, "Connecting to the dive computer")
        scope.launch {
            onDownloadActive(true)
            val result = try {
                runCatching {
                    controller.download(
                        type = type,
                        portId = portId,
                        cancel = { cancelDownload.value },
                        onProgress = { fraction, label -> downloadUi = DownloadUiState.Running(fraction, label) },
                        confirmMerge = { review ->
                            val answer = CompletableDeferred<Boolean>()
                            downloadUi = DownloadUiState.Reviewing(review) { answer.complete(it) }
                            answer.await().also { downloadUi = DownloadUiState.Running(1f, "Importing dives") }
                        },
                    )
                }.getOrElse { "Download failed: ${it.message ?: it::class.simpleName}" }
            } finally {
                onDownloadActive(false)
            }
            downloadUi = DownloadUiState.Hidden
            onDownloaded()
            snackbarHostState.showSnackbar(result)
        }
    }

    val onDownloadClick: () -> Unit = {
        if (serialPorts.downloadSupported) {
            scope.launch {
                onPrepareDownload?.invoke()
                pickerPorts = serialPorts.list()
                downloadUi = DownloadUiState.Picker
            }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Download is not available on this device.") }
        }
    }

    // Hosts that lack a native toast (desktop, iOS) surface import/cloud results here.
    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onStatusShown()
    }

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
                    initialCloudEmail = initialCloudEmail,
                    initialCloudPassword = initialCloudPassword,
                    onCloudConfigChange = onCloudConfigChange,
                    onCloudPull = onCloudPull,
                    onCloudPush = onCloudPush,
                )
            }

            when (val ui = downloadUi) {
                DownloadUiState.Hidden -> {}
                DownloadUiState.Picker -> DownloadPickerDialog(
                    types = downloadTypes,
                    ports = pickerPorts,
                    onRefresh = { serialPorts.list() },
                    onStart = { type, portId -> startSerialDownload(type, portId) },
                    onCancel = { downloadUi = DownloadUiState.Hidden },
                )
                is DownloadUiState.Running -> DownloadProgressDialog(
                    state = ui,
                    onCancel = { cancelDownload.value = true },
                )
                is DownloadUiState.Reviewing -> DownloadReviewDialog(ui)
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
