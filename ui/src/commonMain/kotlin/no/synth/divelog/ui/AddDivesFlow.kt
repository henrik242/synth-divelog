package no.synth.divelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.logbook.download.DiveAction
import no.synth.divelog.core.logbook.download.DiveComputerType
import no.synth.divelog.core.logbook.download.DownloadAmount
import no.synth.divelog.core.logbook.download.DownloadController
import no.synth.divelog.core.logbook.download.KnownComputer
import no.synth.divelog.core.logbook.download.SerialPortInfo
import no.synth.divelog.core.logbook.reason
import no.synth.divelog.core.logbook.sync.CloudSync
import no.synth.divelog.core.model.Dive
import no.synth.divelog.ui.components.CloudCredentialsDialog
import no.synth.divelog.ui.download.DownloadPickerDialog
import no.synth.divelog.ui.download.DownloadProgressDialog
import no.synth.divelog.ui.download.DownloadReviewDialog
import no.synth.divelog.ui.download.DownloadUiState
import no.synth.divelog.ui.download.connectHint
import kotlin.time.Clock

/**
 * Everything behind the Add dives button: the chooser, the dive-computer download, file
 * import, cloud import and a dive entered by hand, with their dialogs. The platform only supplies the hooks (ports,
 * file picker); the work and progress live here.
 */
internal class AddDivesFlow(
    private val services: AppServices,
    private val hooks: PlatformHooks,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val onManualDive: (diveId: Long) -> Unit,
) {
    private val controller = DownloadController(services.container, hooks.serialPorts, services.settings)
    private val downloadTypes = DiveComputerType.entries.filter { it.reachableOver(hooks.serialPorts.linkKinds) }

    private var chooserOpen by mutableStateOf(false)
    private var cloudDialogOpen by mutableStateOf(false)
    private var downloadUi by mutableStateOf<DownloadUiState>(DownloadUiState.Hidden)
    private var pickerPorts by mutableStateOf<List<SerialPortInfo>>(emptyList())
    private var pickerKnown by mutableStateOf<List<KnownComputer>>(emptyList())
    private var cancelDownload by mutableStateOf(false)
    private var importProgress by mutableStateOf<ImportProgress?>(null)

    fun open() {
        chooserOpen = true
    }

    // Starts now on this device's clock; the edit screen it opens sets the rest.
    private fun addManualDive() {
        val now = Clock.System.now()
        val dives = services.container.dives
        val id = dives.insertDive(
            Dive(
                number = dives.nextDiveNumber(),
                startEpochSeconds = now.epochSeconds / 60 * 60,
                utcOffsetSeconds = TimeZone.currentSystemDefault().offsetAt(now).totalSeconds,
                durationSeconds = 0,
            ),
        )
        onManualDive(id)
    }

    private fun openDownloadPicker() {
        scope.launch {
            hooks.prepareDownload()
            withContext(Dispatchers.Default) {
                pickerPorts = hooks.serialPorts.list()
                pickerKnown = controller.knownComputers(downloadTypes)
            }
            downloadUi = DownloadUiState.Picker
        }
    }

    private fun startDownload(type: DiveComputerType, port: SerialPortInfo, amount: DownloadAmount) {
        cancelDownload = false
        downloadUi = DownloadUiState.Running(0f, "Connecting to the dive computer")
        scope.launch {
            hooks.onDownloadActive(true)
            val result = try {
                runCatching {
                    controller.download(
                        type = type,
                        portId = port.id,
                        cancel = { cancelDownload },
                        amount = amount,
                        portDescriptor = port.descriptor,
                        recordTo = hooks.recordTranscript,
                        onProgress = { fraction, label ->
                            downloadUi = DownloadUiState.Running(fraction, label)
                            hooks.onDownloadProgress(label)
                        },
                        review = { dives ->
                            val answer = CompletableDeferred<List<DiveAction>>()
                            downloadUi = DownloadUiState.Reviewing(dives) { answer.complete(it) }
                            answer.await().also { downloadUi = DownloadUiState.Running(1f, "Importing dives") }
                        },
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    "Download failed: ${it.reason}"
                }
            } finally {
                hooks.onDownloadActive(false)
            }
            downloadUi = DownloadUiState.Hidden
            snackbar.showSnackbar(result)
        }
    }

    private fun importFromFile(pick: suspend () -> ByteArray?) {
        scope.launch {
            val bytes = pick() ?: return@launch
            importProgress = ImportProgress("Reading file", null)
            val message = withContext(Dispatchers.Default) {
                runCatching {
                    services.logbook.importFileMessage(bytes) { done, total -> importProgress = ImportProgress.importing(done, total) }
                }.getOrElse { "Import failed: ${it.reason}" }
            }
            importProgress = null
            snackbar.showSnackbar(message)
        }
    }

    private fun importFromCloud(cloud: CloudSync, email: String, pass: String) {
        scope.launch {
            importProgress = ImportProgress("Connecting to the cloud", null)
            val message = runCatching {
                val files = cloud.pull(email, pass) { task, fraction ->
                    importProgress = ImportProgress("Downloading: $task", fraction)
                }
                importProgress = ImportProgress("Reading the logbook", null)
                withContext(Dispatchers.Default) {
                    services.logbook.cloudImportMessage(files) { done, total -> importProgress = ImportProgress.importing(done, total) }
                }
            }.getOrElse { "Cloud import failed: ${it.reason}" }
            importProgress = null
            snackbar.showSnackbar(message)
        }
    }

    @Composable
    fun Dialogs() {
        when (val ui = downloadUi) {
            DownloadUiState.Hidden -> {}
            DownloadUiState.Picker -> DownloadPickerDialog(
                types = downloadTypes,
                known = pickerKnown,
                lastKey = controller.lastComputerKey(),
                serialPorts = hooks.serialPorts,
                ports = pickerPorts,
                onStart = { type, port, amount -> startDownload(type, port, amount) },
                onCancel = { downloadUi = DownloadUiState.Hidden },
                preselectPortId = { type, ports -> controller.preselectedPortId(type, ports) },
                hint = { type -> listOfNotNull(connectHint(type), hooks.downloadHint(type)).joinToString(" ") },
            )
            is DownloadUiState.Running -> DownloadProgressDialog(state = ui, onCancel = { cancelDownload = true })
            is DownloadUiState.Reviewing -> DownloadReviewDialog(ui, services.settings.unitSystem)
        }

        if (chooserOpen) {
            val pick = hooks.pickImportFile
            AddDivesChooser(
                onDiveComputer = if (hooks.serialPorts.downloadSupported) {
                    { chooserOpen = false; openDownloadPicker() }
                } else {
                    null
                },
                onImportFile = pick?.let { { chooserOpen = false; importFromFile(it) } },
                onSubsurfaceCloud = services.cloud?.let { { chooserOpen = false; cloudDialogOpen = true } },
                onManual = { chooserOpen = false; addManualDive() },
                onDismiss = { chooserOpen = false },
            )
        }

        val cloud = services.cloud
        if (cloudDialogOpen && cloud != null) {
            val settings = services.settings
            CloudCredentialsDialog(
                title = "Import from Subsurface cloud",
                confirmLabel = "Import",
                initialEmail = settings.cloudEmail,
                initialPassword = settings.cloudPassword,
                onConfirm = { email, pass ->
                    settings.cloudEmail = email
                    settings.cloudPassword = pass
                    cloudDialogOpen = false
                    importFromCloud(cloud, email, pass)
                },
                onDismiss = { cloudDialogOpen = false },
            )
        }

        importProgress?.let { ImportProgressDialog(it) }
    }
}

@Composable
internal fun rememberAddDivesFlow(
    services: AppServices,
    hooks: PlatformHooks,
    snackbar: SnackbarHostState,
    onManualDive: (diveId: Long) -> Unit,
): AddDivesFlow {
    val scope = rememberCoroutineScope()
    return remember(services, hooks) { AddDivesFlow(services, hooks, scope, snackbar, onManualDive) }
}

/** One place to get dives in. A null action hides its option; entering a dive by hand is always there. */
@Composable
private fun AddDivesChooser(
    onDiveComputer: (() -> Unit)?,
    onImportFile: (() -> Unit)?,
    onSubsurfaceCloud: (() -> Unit)?,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add dives") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDiveComputer != null) {
                    OutlinedButton(onClick = onDiveComputer, modifier = Modifier.fillMaxWidth()) {
                        Text("Dive computer")
                    }
                }
                if (onImportFile != null) {
                    OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                        Text("Import from file")
                    }
                    Text(
                        "Imports Subsurface XML, UDDF, MacDive XML and Shearwater Cloud database files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onSubsurfaceCloud != null) {
                    OutlinedButton(onClick = onSubsurfaceCloud, modifier = Modifier.fillMaxWidth()) {
                        Text("Import from Subsurface cloud")
                    }
                }
                OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                    Text("Enter manually")
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
