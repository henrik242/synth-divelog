package no.synth.divelog.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private class Attribution(val name: String, val description: String, val licence: String)

private val sources = listOf(
    Attribution("OpenFreeMap / OpenMapTiles", "Map tiles for dive sites", "ODbL, CC BY 4.0"),
    Attribution("OpenStreetMap", "Map data", "ODbL"),
    Attribution("Subsurface", "Buhlmann decompression model, dive planner and gas compressibility", "GPL-2.0"),
    Attribution("libdivecomputer", "Reference for dive computer protocols", "LGPL-2.1"),
    Attribution("Perry's Chemical Engineers' Handbook", "Gas compressibility data", ""),
)

private val libraries = listOf(
    Attribution("Kotlin and kotlinx", "Language, coroutines, date-time and JSON", "Apache 2.0"),
    Attribution("Compose Multiplatform", "UI framework", "Apache 2.0"),
    Attribution("AndroidX", "Android integration", "Apache 2.0"),
    Attribution("SQLDelight", "Local database", "Apache 2.0"),
    Attribution("SQLite", "Database engine", "Public domain"),
    Attribution("SQLite JDBC", "Desktop database driver", "Apache 2.0"),
    Attribution("SQLiter", "iOS database driver", "Apache 2.0"),
    Attribution("xmlutil", "XML logbook formats", "Apache 2.0"),
    Attribution("MapLibre Compose", "Map view", "BSD 3-Clause"),
    Attribution("MapLibre Native", "Map rendering", "BSD 2-Clause"),
    Attribution("JGit", "Subsurface cloud sync", "EDL 1.0"),
    Attribution("jSerialComm", "Desktop serial ports", "Apache 2.0 / LGPL 3.0"),
    Attribution("usb-serial-for-android", "Android USB serial", "MIT"),
)

/** Data sources, references and open-source libraries the app builds on. */
@Composable
fun AttributionsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader("Data and references")
        sources.forEach { AttributionItem(it) }
        SectionHeader("Open-source libraries")
        libraries.forEach { AttributionItem(it) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun AttributionItem(item: Attribution) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            if (item.licence.isEmpty()) item.name else "${item.name} (${item.licence})",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            item.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
