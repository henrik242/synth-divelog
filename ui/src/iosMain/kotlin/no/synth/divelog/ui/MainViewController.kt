package no.synth.divelog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.ui.io.LogbookIo
import no.synth.divelog.ui.io.pickDocument
import no.synth.divelog.ui.io.shareDocument
import no.synth.divelog.ui.settings.AppSettings
import no.synth.divelog.ui.settings.SettingsStore
import no.synth.divelog.ui.sync.CloudGit
import platform.UIKit.UIViewController

/**
 * iOS entry point: hosts the shared Compose UI in a UIViewController for the Swift
 * app to present. Reuses the shared repositories, settings and cloud sync. Dive-computer
 * download is unavailable on iOS (no serial layer), so the Download button says so. Files
 * are imported through the document picker and exported through the share sheet.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val settings = remember { AppSettings(SettingsStore()) }
    val container = remember { AppContainer(DriverFactory().createDatabase()) }
    val logbook = remember { LogbookIo(container) }
    val cloud = remember { CloudGit("") }
    val scope = rememberCoroutineScope()
    var unitSystem by remember { mutableStateOf(settings.unitSystem) }
    var dataVersion by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val host = LocalUIViewController.current

    SynthTheme {
        SynthDivelogApp(
            container = container,
            unitSystem = unitSystem,
            onUnitSystemChange = {
                unitSystem = it
                settings.unitSystem = it
            },
            onReparse = {
                scope.launch {
                    val count = withContext(Dispatchers.Default) { logbook.reparseAll() }
                    dataVersion++
                    status = "Re-parsed $count dives"
                }
            },
            onDownloaded = { dataVersion++ },
            onPickImportFile = { pickDocument(host) },
            onExport = { formatId ->
                scope.launch {
                    val export = withContext(Dispatchers.Default) { runCatching { logbook.export(formatId) } }
                    export.onSuccess { file -> file?.let { shareDocument(host, it) } }
                        .onFailure { status = "Export failed: ${it.message ?: it::class.simpleName}" }
                }
            },
            cloudEnabled = true,
            initialCloudEmail = settings.cloudEmail,
            initialCloudPassword = settings.cloudPassword,
            onCloudConfigChange = { email, pass ->
                settings.cloudEmail = email
                settings.cloudPassword = pass
            },
            cloud = cloud,
            onCloudPush = { email, pass ->
                scope.launch {
                    status = runCatching {
                        val tree = withContext(Dispatchers.Default) { logbook.exportCloudTree() }
                        cloud.push(email, pass, tree)
                        "Pushed to the cloud"
                    }.getOrElse { "Push failed: ${it.message ?: it::class.simpleName}" }
                }
            },
            statusMessage = status,
            onStatusShown = { status = null },
            dataVersion = dataVersion,
        )
    }
}
