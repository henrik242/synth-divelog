package no.synth.divelog.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.SynthDivelogApp
import no.synth.divelog.ui.SynthTheme
import no.synth.divelog.ui.io.LogbookIo
import no.synth.divelog.ui.settings.AppSettings
import no.synth.divelog.ui.settings.SettingsStore
import no.synth.divelog.ui.sync.CloudSync
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop (JVM) entry point. Reuses the shared Compose UI and core repositories
 * over a file-backed SQLite database in the user's home. Wired-serial download is
 * Android-only for now; file import/export, re-parse and cloud sync go through the
 * shared logbook and sync code.
 */
fun main() = application {
    val settings = remember { AppSettings(SettingsStore()) }
    val container = remember { AppContainer(DriverFactory(databasePath()).createDatabase()) }
    val logbook = remember { LogbookIo(container) }
    val scope = rememberCoroutineScope()
    var unitSystem by remember { mutableStateOf(settings.unitSystem) }
    var dataVersion by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    Window(onCloseRequest = ::exitApplication, title = "Synth Divelog") {
        SynthTheme {
            SynthDivelogApp(
                container = container,
                unitSystem = unitSystem,
                onUnitSystemChange = {
                    unitSystem = it
                    settings.unitSystem = it
                },
                onDownloadClick = {}, // Wired-serial download is Android-only for now.
                onImport = {
                    val path = pickFile(FileDialog.LOAD)
                    if (path != null) {
                        val text = File(path).readText()
                        status = runCatching { logbook.importMessage(text) }
                            .getOrElse { e -> "Import failed: ${e.message ?: e::class.simpleName}" }
                        dataVersion++
                    }
                },
                onExport = { formatId -> exportToFile(logbook, formatId) },
                onReparse = {
                    val count = logbook.reparseAll()
                    dataVersion++
                    status = "Re-parsed $count dives"
                },
                cloudEnabled = true,
                initialCloudUrl = settings.cloudUrl,
                initialCloudUsername = settings.cloudUsername,
                initialCloudPassword = settings.cloudPassword,
                onCloudConfigChange = { url, user, pass ->
                    settings.cloudUrl = url
                    settings.cloudUsername = user
                    settings.cloudPassword = pass
                },
                onCloudPull = { url, user, pass ->
                    scope.launch {
                        status = runCatching { logbook.cloudPullMessage(CloudSync.pull(url, user, pass)) }
                            .getOrElse { "Pull failed: ${it.message ?: it::class.simpleName}" }
                        dataVersion++
                    }
                },
                onCloudPush = { url, user, pass ->
                    scope.launch {
                        status = runCatching {
                            val format = LogbookIo.formats().first { it.id == "subsurface-xml" }
                            CloudSync.push(url, user, pass, logbook.exportAll(format))
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
}

/** Database file under the user's home, created on first run. */
private fun databasePath(): String {
    val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    return File(dir, "divelog.db").absolutePath
}

private fun exportToFile(logbook: LogbookIo, formatId: String) {
    val format = LogbookIo.formats().firstOrNull { it.id == formatId } ?: return
    val ext = if (formatId == "uddf") "uddf" else "xml"
    val dialog = FileDialog(null as Frame?, "Export logbook", FileDialog.SAVE).apply {
        file = "synth-divelog.$ext"
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val name = dialog.file ?: return
    File(dir, name).writeText(logbook.exportAll(format))
}

/** Opens a file dialog, returning the chosen absolute path or null if cancelled. */
private fun pickFile(mode: Int): String? {
    val dialog = FileDialog(null as Frame?, "Import logbook", mode).apply { isVisible = true }
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name).absolutePath
}
