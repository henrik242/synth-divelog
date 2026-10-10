package no.synth.divelog.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.logbook.download.DesktopSerialPorts
import no.synth.divelog.core.logbook.download.SimulatedSerialPorts
import no.synth.divelog.core.logbook.io.ExportFile
import no.synth.divelog.core.logbook.settings.JavaPreferencesStore
import no.synth.divelog.core.logbook.sync.CloudGit
import no.synth.divelog.ui.PlatformHooks
import no.synth.divelog.ui.SynthDivelogApp
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop (JVM) entry point: the shared app over a SQLite database and cloud working
 * copies under ~/.synth-divelog, jSerialComm ports, and AWT file dialogs.
 */
fun main() {
    val home = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    val services = AppServices(
        database = createDatabase(File(home, "divelog.db").absolutePath),
        settingsStore = JavaPreferencesStore(),
        cloud = CloudGit(File(home, "cloud").absolutePath),
    )
    val hooks = PlatformHooks(
        // SYNTH_DIVELOG_SIMULATOR=1 adds a simulated dive computer to the download ports.
        serialPorts = DesktopSerialPorts().let { if (System.getenv("SYNTH_DIVELOG_SIMULATOR") == "1") SimulatedSerialPorts(it) else it },
        pickImportFile = { withContext(Dispatchers.IO) { chooseFile("Import logbook", FileDialog.LOAD)?.readBytes() } },
        saveExport = ::saveExport,
    )
    application {
        Window(onCloseRequest = ::exitApplication, title = "Synth Divelog") {
            // The desktop MapLibre map renders into a presentation host bound to this AWT
            // window; it must sit above any map in the tree (see SiteLocationMap).
            ProvideMapPresentationHost(host = rememberAwtComposeMapPresentationHost(window)) {
                SynthDivelogApp(services, hooks)
            }
        }
    }
}

private suspend fun saveExport(export: ExportFile) = withContext(Dispatchers.IO) {
    chooseFile("Export logbook", FileDialog.SAVE, export.name)?.writeBytes(export.bytes)
}

/** Opens a file dialog, returning the chosen file or null if cancelled. */
private fun chooseFile(title: String, mode: Int, suggestedName: String? = null): File? {
    val dialog = FileDialog(null as Frame?, title, mode).apply {
        suggestedName?.let { file = it }
        isVisible = true
    }
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}
