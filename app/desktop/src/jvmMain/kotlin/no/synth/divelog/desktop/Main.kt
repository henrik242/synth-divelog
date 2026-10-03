package no.synth.divelog.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.SynthDivelogApp
import no.synth.divelog.ui.io.LogbookIo
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.prefs.Preferences

/**
 * Desktop (JVM) entry point. Reuses the shared Compose UI and core repositories
 * over a file-backed SQLite database in the user's home. Bluetooth download is
 * Android-only for now (a serial/USB transport is a later milestone); import,
 * export and re-parse work through the shared logbook operations.
 */
fun main() = application {
    val prefs = remember { Preferences.userRoot().node("no/synth/divelog") }
    val container = remember { AppContainer(DriverFactory(databasePath()).createDatabase()) }
    val logbook = remember { LogbookIo(container) }
    var unitSystem by remember {
        mutableStateOf(
            runCatching { UnitSystem.valueOf(prefs.get("unitSystem", UnitSystem.METRIC.name)) }
                .getOrDefault(UnitSystem.METRIC),
        )
    }
    var dataVersion by remember { mutableStateOf(0) }

    Window(onCloseRequest = ::exitApplication, title = "Synth Divelog") {
        MaterialTheme {
            SynthDivelogApp(
                container = container,
                unitSystem = unitSystem,
                onUnitSystemChange = {
                    unitSystem = it
                    prefs.put("unitSystem", it.name)
                },
                onDownloadClick = {}, // Bluetooth download is Android-only for now.
                onImport = { if (importFromFile(logbook)) dataVersion++ },
                onExport = { formatId -> exportToFile(logbook, formatId) },
                onReparse = { logbook.reparseAll(); dataVersion++ },
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

/** Returns true if something was imported (so the caller can refresh). */
private fun importFromFile(logbook: LogbookIo): Boolean {
    val dialog = FileDialog(null as Frame?, "Import logbook", FileDialog.LOAD).apply { isVisible = true }
    val dir = dialog.directory ?: return false
    val name = dialog.file ?: return false
    val text = File(dir, name).readText()
    val format = LogbookIo.detect(text) ?: return false
    logbook.import(format, text)
    return true
}
