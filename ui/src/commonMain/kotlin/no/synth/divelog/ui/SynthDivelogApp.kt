package no.synth.divelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import no.synth.divelog.ui.common.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.Country
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.buddies.BuddiesSection
import no.synth.divelog.ui.tags.TagsSection
import no.synth.divelog.ui.dive.DiveDetailScreen
import no.synth.divelog.ui.dive.DiveEditScreen
import no.synth.divelog.ui.dive.DiveRow
import no.synth.divelog.ui.dive.diveSourceLabels
import no.synth.divelog.ui.components.Breadcrumb
import no.synth.divelog.ui.components.CloudCredentialsDialog
import no.synth.divelog.ui.components.Crumb
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.download.ConnectionMemory
import no.synth.divelog.ui.download.DiveComputerType
import no.synth.divelog.ui.download.DownloadAmount
import no.synth.divelog.ui.download.DownloadController
import no.synth.divelog.ui.download.DownloadPickerDialog
import no.synth.divelog.ui.download.DownloadProgressDialog
import no.synth.divelog.ui.download.DownloadReviewDialog
import no.synth.divelog.ui.download.DownloadUiState
import no.synth.divelog.ui.download.KnownComputer
import no.synth.divelog.ui.download.NoSerialPorts
import no.synth.divelog.ui.download.SerialPortInfo
import no.synth.divelog.ui.download.SerialPorts
import no.synth.divelog.ui.format.Format
import no.synth.divelog.ui.io.LogbookIo
import no.synth.divelog.ui.settings.ComputersScreen
import no.synth.divelog.ui.settings.SettingsSection
import no.synth.divelog.ui.sites.SitesSection
import no.synth.divelog.ui.stats.StatisticsSection
import no.synth.divelog.ui.sync.CloudGit
import no.synth.divelog.ui.tools.GasBlenderState
import no.synth.divelog.ui.tools.ModEndState
import no.synth.divelog.ui.tools.TankBuoyancyState
import no.synth.divelog.ui.tools.Tool
import no.synth.divelog.ui.tools.ToolsSection

private enum class Section(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    DIVES("Dives", Icons.Outlined.Waves, Icons.Filled.Waves),
    SITES("Sites", Icons.Outlined.Place, Icons.Filled.Place),
    BUDDIES("Buddies", Icons.Outlined.Group, Icons.Filled.Group),
    TAGS("Tags", Icons.Outlined.Sell, Icons.Filled.Sell),
    STATS("Stats", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    TOOLS("Tools", Icons.Outlined.Build, Icons.Filled.Build),
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
 *
 * File import is co-located with the download under the Add-dives button. [onPickImportFile],
 * if set, picks and reads a file and returns its text (or null if cancelled); the shared app
 * then runs the import with a progress dialog. A null value hides the file-import option.
 * Cloud import runs here too, over [cloud], behind the same dialog.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SynthDivelogApp(
    container: AppContainer,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    serialPorts: SerialPorts = NoSerialPorts(),
    connectionMemory: ConnectionMemory = ConnectionMemory.None,
    downloadTypes: List<DiveComputerType> = DiveComputerType.entries,
    onPrepareDownload: (suspend () -> Unit)? = null,
    onDownloadActive: (Boolean) -> Unit = {},
    onDownloadProgress: (label: String) -> Unit = {},
    onRecordTranscript: ((transcript: String) -> Unit)? = null,
    onDownloaded: () -> Unit = {},
    onPickImportFile: (suspend () -> String?)? = null,
    onExport: (formatId: String) -> Unit = {},
    onReparse: () -> Unit = {},
    cloudEnabled: Boolean = false,
    initialCloudEmail: String = "",
    initialCloudPassword: String = "",
    onCloudConfigChange: (email: String, pass: String) -> Unit = { _, _ -> },
    cloud: CloudGit? = null,
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
    // Sites drill-in state is lifted here too, so the top bar breadcrumb and the root
    // back handler drive it the same way the dive state does.
    var siteCountry by remember(dataVersion) { mutableStateOf<Country?>(null) }
    var sitePlace by remember(dataVersion) { mutableStateOf<Place?>(null) }
    var siteOpen by remember(dataVersion) { mutableStateOf<Site?>(null) }
    var siteEditing by remember(dataVersion) { mutableStateOf(false) }
    // The Dive computers page is a drill-in over the current section (Settings, or Dives via a
    // dive-detail link), so back returns to where it was opened from. The focus id is set by
    // the dive-detail computer link: the page scrolls to and highlights that device.
    var computersOpen by remember { mutableStateOf(false) }
    var computersFocusDeviceId by remember { mutableStateOf<Long?>(null) }
    var pendingSiteId by remember { mutableStateOf<Long?>(null) }
    var toolOpen by remember { mutableStateOf<Tool?>(null) }
    // Actions a screen puts in the top bar beside the breadcrumb (the dive detail's
    // previous/next and menu); the screen sets them while shown and clears them on leaving.
    var topBarActions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    val blender = remember { GasBlenderState() }
    val tank = remember { TankBuoyancyState() }
    val modEnd = remember { ModEndState() }

    // A dive's site link sets this: drill straight to that site, deriving its place and
    // country so the breadcrumb and the back steps still work.
    LaunchedEffect(pendingSiteId) {
        val id = pendingSiteId ?: return@LaunchedEffect
        container.sites.site(id)?.let { s ->
            val p = container.sites.place(s.placeId)
            siteCountry = p?.let { container.sites.country(it.countryId) }
            sitePlace = p
            siteOpen = s
            siteEditing = false
        }
        pendingSiteId = null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var backArmed by remember { mutableStateOf(false) }

    // Shared wired-serial download: picker, progress and orchestration over [serialPorts].
    val controller = remember(container, serialPorts, connectionMemory) {
        DownloadController(container, serialPorts, connectionMemory)
    }
    var downloadUi by remember { mutableStateOf<DownloadUiState>(DownloadUiState.Hidden) }
    var pickerPorts by remember { mutableStateOf<List<SerialPortInfo>>(emptyList()) }
    var pickerKnown by remember { mutableStateOf<List<KnownComputer>>(emptyList()) }
    val cancelDownload = remember { mutableStateOf(false) }

    fun startSerialDownload(type: DiveComputerType, port: SerialPortInfo, amount: DownloadAmount) {
        cancelDownload.value = false
        downloadUi = DownloadUiState.Running(0f, "Connecting to the dive computer")
        scope.launch {
            onDownloadActive(true)
            val result = try {
                runCatching {
                    controller.download(
                        type = type,
                        portId = port.id,
                        cancel = { cancelDownload.value },
                        amount = amount,
                        portDescriptor = port.descriptor,
                        recordTo = onRecordTranscript,
                        onProgress = { fraction, label ->
                            downloadUi = DownloadUiState.Running(fraction, label)
                            onDownloadProgress(label)
                        },
                        reviewMerges = { reviews ->
                            val answer = CompletableDeferred<Set<Int>>()
                            downloadUi = DownloadUiState.Reviewing(reviews) { answer.complete(it) }
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
                withContext(Dispatchers.Default) {
                    pickerPorts = serialPorts.list()
                    pickerKnown = controller.knownComputers(downloadTypes)
                }
                downloadUi = DownloadUiState.Picker
            }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Download is not available on this device.") }
        }
    }

    // Shared file import: the platform only picks and reads the file; the import itself runs
    // here off the main thread behind a progress dialog, mirroring the download flow.
    val logbook = remember(container) { LogbookIo(container) }
    var addDivesOpen by remember { mutableStateOf(false) }
    var cloudImportOpen by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }

    val onImportFromFile: () -> Unit = {
        val pick = onPickImportFile
        if (pick == null) {
            scope.launch { snackbarHostState.showSnackbar("File import is not available on this device.") }
        } else {
            scope.launch {
                val text = pick()
                if (text != null) {
                    importProgress = ImportProgress("Reading file", null)
                    val message = withContext(Dispatchers.Default) {
                        runCatching {
                            logbook.importMessage(text) { done, total -> importProgress = ImportProgress.importing(done, total) }
                        }.getOrElse { "Import failed: ${it.message ?: it::class.simpleName}" }
                    }
                    importProgress = null
                    onDownloaded()
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    // Cloud import: download the logbook, then import it, behind the same progress dialog.
    val onCloudImport: (email: String, pass: String) -> Unit = { email, pass ->
        val client = cloud
        if (client == null) {
            scope.launch { snackbarHostState.showSnackbar("Cloud import is not available on this device.") }
        } else {
            scope.launch {
                importProgress = ImportProgress("Connecting to the cloud", null)
                val message = runCatching {
                    val files = client.pull(email, pass) { task, fraction ->
                        importProgress = ImportProgress("Downloading: $task", fraction)
                    }
                    importProgress = ImportProgress("Reading the logbook", null)
                    withContext(Dispatchers.Default) {
                        logbook.cloudImportMessage(files) { done, total -> importProgress = ImportProgress.importing(done, total) }
                    }
                }.getOrElse { "Cloud import failed: ${it.message ?: it::class.simpleName}" }
                importProgress = null
                onDownloaded()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    // Hosts that lack a native toast (desktop, iOS) surface import/cloud results here.
    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onStatusShown()
    }

    // The open dive's label for the breadcrumb; re-read when leaving the editor so an
    // edited dive number shows. Kept unconditional so it stays positionally stable.
    val diveNumberLabel = remember(openDiveId, editing, dataVersion) {
        openDiveId?.let { id -> container.dives.getDive(id)?.number?.let { "Dive #$it" } ?: "Dive" }
    }

    // One breadcrumb for the drill-in sections (Dives, Sites); other sections keep their
    // plain label. Every crumb but the last navigates to that level when tapped.
    val crumbs: List<Crumb> = when (section) {
        Section.DIVES -> buildList {
            add(Crumb("Dives") { editing = false; openDiveId = null })
            if (openDiveId != null) add(Crumb(diveNumberLabel ?: "Dive") { editing = false })
            if (openDiveId != null && editing) add(Crumb("Edit"))
        }
        Section.SITES -> buildList {
            add(Crumb("Sites") { siteCountry = null; sitePlace = null; siteOpen = null; siteEditing = false })
            siteCountry?.let { c -> add(Crumb(c.name) { sitePlace = null; siteOpen = null; siteEditing = false }) }
            sitePlace?.let { p -> add(Crumb(p.name) { siteOpen = null; siteEditing = false }) }
            // Re-read the site when leaving the editor so autosaved edits show in the detail.
            siteOpen?.let { s ->
                add(Crumb(s.name) { container.sites.site(s.id)?.let { siteOpen = it }; siteEditing = false })
            }
            if (siteEditing) add(Crumb("Edit"))
        }
        Section.TOOLS -> buildList {
            add(Crumb("Tools") { toolOpen = null })
            toolOpen?.let { add(Crumb(it.label)) }
        }
        else -> listOf(Crumb(section.label))
    }.let { base ->
        if (!computersOpen) {
            base
        } else {
            // Parent crumbs close the page first; the page itself is the last, inert crumb.
            val parents = base.map { c ->
                val go = c.onClick
                Crumb(c.label) { computersOpen = false; go?.invoke() }
            }
            parents + Crumb("Dive computers")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Breadcrumb(crumbs) },
                actions = { topBarActions?.invoke(this) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { s ->
                    NavigationBarItem(
                        selected = section == s,
                        onClick = { computersOpen = false; section = s },
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
        // Edge-to-edge: the window no longer shrinks for the keyboard, so pad the content by the
        // keyboard height, less the bottom bar it covers, to keep the end of a form reachable.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            // Root-level back. Pops one breadcrumb level first (matching the crumbs),
            // then falls through to the section/exit logic.
            BackHandler {
                when {
                    computersOpen -> computersOpen = false
                    // Dives: edit -> detail -> list.
                    diveDrilledIn && editing -> editing = false
                    diveDrilledIn -> openDiveId = null
                    // Sites: edit -> site -> place -> country -> list. Leaving the editor
                    // re-reads the site so the detail shows the autosaved edits.
                    section == Section.SITES && siteEditing -> {
                        siteOpen?.let { container.sites.site(it.id) }?.let { siteOpen = it }
                        siteEditing = false
                    }
                    section == Section.SITES && siteOpen != null -> siteOpen = null
                    section == Section.SITES && sitePlace != null -> sitePlace = null
                    section == Section.SITES && siteCountry != null -> siteCountry = null
                    section == Section.TOOLS && toolOpen != null -> toolOpen = null
                    section != Section.DIVES -> section = Section.DIVES
                    backArmed -> onExit()
                    else -> {
                        backArmed = true
                        scope.launch { snackbarHostState.showSnackbar("Press back again to exit") }
                        scope.launch { delay(2000); backArmed = false }
                    }
                }
            }
            if (computersOpen) {
                ComputersScreen(
                    container = container,
                    onBack = { computersOpen = false },
                    focusDeviceId = computersFocusDeviceId,
                    onFocusConsumed = { computersFocusDeviceId = null },
                )
            }
            // The open dive and sites state are lifted above, so they survive the page being shown.
            if (!computersOpen) when (section) {
                Section.DIVES -> DivesSection(
                    container, unitSystem, { addDivesOpen = true }, dataVersion,
                    openDiveId = openDiveId,
                    onOpenDiveChange = { openDiveId = it },
                    editing = editing,
                    onEditingChange = { editing = it },
                    onOpenDevice = { deviceId -> computersFocusDeviceId = deviceId; computersOpen = true },
                    onOpenSite = { siteId -> pendingSiteId = siteId; section = Section.SITES },
                    onTopBarActions = { topBarActions = it },
                )
                Section.SITES -> SitesSection(
                    container = container,
                    unitSystem = unitSystem,
                    country = siteCountry,
                    place = sitePlace,
                    site = siteOpen,
                    editing = siteEditing,
                    onCountryChange = { siteCountry = it },
                    onPlaceChange = { sitePlace = it },
                    onSiteChange = { siteOpen = it },
                    onEditingChange = { siteEditing = it },
                )
                Section.BUDDIES -> BuddiesSection(container, unitSystem, dataVersion)
                Section.TAGS -> TagsSection(container, unitSystem, dataVersion)
                Section.STATS -> StatisticsSection(container, unitSystem, dataVersion)
                Section.TOOLS -> ToolsSection(toolOpen, { toolOpen = it }, blender, tank, modEnd, unitSystem)
                Section.SETTINGS -> SettingsSection(
                    container = container,
                    unitSystem = unitSystem,
                    onUnitSystemChange = onUnitSystemChange,
                    onExport = onExport,
                    onReparse = onReparse,
                    cloudEnabled = cloudEnabled,
                    initialCloudEmail = initialCloudEmail,
                    initialCloudPassword = initialCloudPassword,
                    onCloudConfigChange = onCloudConfigChange,
                    onCloudPush = onCloudPush,
                    onOpenComputers = { computersFocusDeviceId = null; computersOpen = true },
                )
            }

            when (val ui = downloadUi) {
                DownloadUiState.Hidden -> {}
                DownloadUiState.Picker -> DownloadPickerDialog(
                    types = downloadTypes,
                    known = pickerKnown,
                    lastKey = controller.lastComputerKey(),
                    ports = pickerPorts,
                    onRefresh = { serialPorts.list() },
                    onStart = { type, port, amount -> startSerialDownload(type, port, amount) },
                    onCancel = { downloadUi = DownloadUiState.Hidden },
                    preselectPortId = { type -> controller.preselectedPortId(type, pickerPorts) },
                )
                is DownloadUiState.Running -> DownloadProgressDialog(
                    state = ui,
                    onCancel = { cancelDownload.value = true },
                )
                is DownloadUiState.Reviewing -> DownloadReviewDialog(ui)
            }

            if (addDivesOpen) {
                AddDivesChooser(
                    importSupported = onPickImportFile != null,
                    cloudSupported = cloudEnabled,
                    onDiveComputer = { addDivesOpen = false; onDownloadClick() },
                    onImportFile = { addDivesOpen = false; onImportFromFile() },
                    onSubsurfaceCloud = { addDivesOpen = false; cloudImportOpen = true },
                    onDismiss = { addDivesOpen = false },
                )
            }

            if (cloudImportOpen) {
                CloudCredentialsDialog(
                    title = "Import from Subsurface cloud",
                    confirmLabel = "Import",
                    initialEmail = initialCloudEmail,
                    initialPassword = initialCloudPassword,
                    onConfirm = { email, pass ->
                        onCloudConfigChange(email, pass)
                        cloudImportOpen = false
                        onCloudImport(email, pass)
                    },
                    onDismiss = { cloudImportOpen = false },
                )
            }

            importProgress?.let { p -> ImportProgressDialog(p) }
        }
    }
}

/** One place to get dives in: the download picker or a file import. */
@Composable
private fun AddDivesChooser(
    importSupported: Boolean,
    cloudSupported: Boolean,
    onDiveComputer: () -> Unit,
    onImportFile: () -> Unit,
    onSubsurfaceCloud: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add dives") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDiveComputer, modifier = Modifier.fillMaxWidth()) {
                    Text("Dive computer")
                }
                if (importSupported) {
                    OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                        Text("Import from file")
                    }
                    Text(
                        "Imports Subsurface XML, UDDF and MacDive XML files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cloudSupported) {
                    OutlinedButton(onClick = onSubsurfaceCloud, modifier = Modifier.fillMaxWidth()) {
                        Text("Import from Subsurface cloud")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The current step of a file or cloud import; [fraction] is null while the size is unknown. */
private data class ImportProgress(val step: String, val fraction: Float?) {
    companion object {
        fun importing(done: Int, total: Int) =
            if (total > 0) ImportProgress("Importing $done of $total", done.toFloat() / total) else ImportProgress("Importing", null)
    }
}

/** Modal progress while an import runs; determinate once the step's size is known. */
@Composable
private fun ImportProgressDialog(progress: ImportProgress) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Importing dives") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(progress.step)
                val fraction = progress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
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
    onOpenDevice: (Long) -> Unit = {},
    onOpenSite: (Long) -> Unit = {},
    onTopBarActions: ((@Composable RowScope.() -> Unit)?) -> Unit = {},
) {
    var reloadKey by remember(dataVersion) { mutableStateOf(0) }

    // Leaving the autosaving editor (via breadcrumb or back) re-reads the dive so the
    // detail and the list show the saved edits.
    LaunchedEffect(editing) { if (!editing) reloadKey++ }

    val dives = remember(dataVersion, reloadKey) { container.dives.allDives() }
    val siteNames = remember(dataVersion, reloadKey) { container.sites.allSites().associate { it.id to it.name } }
    val sources = remember(dataVersion, reloadKey) { diveSourceLabels(container) }
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

    val currentDive = openDiveId
    if (currentDive != null) {
        if (editing) {
            DiveEditScreen(
                container = container,
                diveId = currentDive,
                unitSystem = unitSystem,
                onCancel = { onEditingChange(false) },
            )
        } else {
            DiveDetailScreen(
                container = container,
                diveId = currentDive,
                unitSystem = unitSystem,
                reloadKey = reloadKey,
                // Prev/next walk the filtered list behind this screen. It is sorted descending
                // (newest, deepest, ... first), so reverse it: right goes newer, left older.
                orderedDiveIds = shown.map { it.id }.asReversed(),
                onNavigate = { onOpenDiveChange(it) },
                onOpenDevice = onOpenDevice,
                onOpenSite = onOpenSite,
                onEdit = { onEditingChange(true) },
                onChanged = { reloadKey++ },
                onDeleted = { onOpenDiveChange(null); reloadKey++ },
                onTopBarActions = onTopBarActions,
            )
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        if (dives.isEmpty()) {
            EmptyState("No dives yet. Tap Add dives to download from a computer or import a file.", Icons.Outlined.Waves)
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
                            DiveRow(dive, unitSystem, onClick = { onOpenDiveChange(dive.id) }, siteName = dive.siteId?.let { siteNames[it] }, source = sources[dive.id])
                            HorizontalDivider()
                        }
                    }
                } else {
                    items(shown) { dive ->
                        DiveRow(dive, unitSystem, onClick = { onOpenDiveChange(dive.id) }, siteName = dive.siteId?.let { siteNames[it] }, source = sources[dive.id])
                        HorizontalDivider()
                    }
                }
            }
        }
      }
      ExtendedFloatingActionButton(
          onClick = onDownloadClick,
          icon = { Icon(Icons.Filled.Add, contentDescription = null) },
          text = { Text("Add dives") },
          modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
      )
    }
}

private enum class DiveSort(val label: String) { DATE("Date"), NUMBER("Number"), DEPTH("Depth"), DURATION("Duration") }
