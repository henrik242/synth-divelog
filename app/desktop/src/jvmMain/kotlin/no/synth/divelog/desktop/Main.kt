package no.synth.divelog.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.SynthDivelogApp
import no.synth.divelog.ui.SynthTheme
import no.synth.divelog.ui.download.DesktopSerialPorts
import no.synth.divelog.ui.io.LogbookIo
import no.synth.divelog.ui.settings.AppSettings
import no.synth.divelog.ui.settings.SettingsConnectionMemory
import no.synth.divelog.ui.settings.SettingsStore
import no.synth.divelog.ui.sync.CloudGit
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop (JVM) entry point. Reuses the shared Compose UI and core repositories
 * over a file-backed SQLite database in the user's home. The Download button runs the
 * shared wired-serial download over jSerialComm; file import/export, re-parse and cloud
 * sync go through the shared logbook and sync code.
 */
fun main() = application {
    val settings = remember { AppSettings(SettingsStore()) }
    val container = remember { AppContainer(DriverFactory(databasePath()).createDatabase()) }
    val logbook = remember { LogbookIo(container) }
    val cloud = remember { CloudGit(cloudDir()) }
    val serialPorts = remember { DesktopSerialPorts() }
    val scope = rememberCoroutineScope()
    var unitSystem by remember { mutableStateOf(settings.unitSystem) }
    var dataVersion by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    Window(onCloseRequest = ::exitApplication, title = "Synth Divelog") {
        // The desktop MapLibre map renders into a presentation host bound to this AWT
        // window; it must sit above any map in the tree (see SiteLocationMap).
        ProvideMapPresentationHost(host = rememberAwtComposeMapPresentationHost(window)) {
            SynthTheme {
                SynthDivelogApp(
                    container = container,
                    unitSystem = unitSystem,
                    onUnitSystemChange = {
                        unitSystem = it
                        settings.unitSystem = it
                    },
                    serialPorts = serialPorts,
                    connectionMemory = remember { SettingsConnectionMemory(settings) },
                    onDownloaded = { dataVersion++ },
                    // Pick and read the file off the UI thread; the shared app runs the import.
                    onPickImportFile = { withContext(Dispatchers.IO) { pickFile(FileDialog.LOAD)?.let { File(it).readBytes() } } },
                    onExport = { formatId -> exportToFile(logbook, formatId) },
                    onReparse = {
                        val count = logbook.reparseAll()
                        dataVersion++
                        status = "Re-parsed $count dives"
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
                                cloud.push(email, pass, logbook.exportCloudTree())
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
}

/** Database file under the user's home, created on first run. */
private fun databasePath(): String {
    val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    return File(dir, "divelog.db").absolutePath
}

/** Directory holding the cloud working copies, under the user's home. */
private fun cloudDir(): String =
    File(System.getProperty("user.home"), ".synth-divelog/cloud").apply { mkdirs() }.absolutePath

private fun exportToFile(logbook: LogbookIo, formatId: String) {
    val export = logbook.export(formatId) ?: return
    val dialog = FileDialog(null as Frame?, "Export logbook", FileDialog.SAVE).apply {
        file = export.name
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val name = dialog.file ?: return
    File(dir, name).writeBytes(export.bytes)
}

/** Opens a file dialog, returning the chosen absolute path or null if cancelled. */
private fun pickFile(mode: Int): String? {
    val dialog = FileDialog(null as Frame?, "Import logbook", mode).apply { isVisible = true }
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name).absolutePath
}
