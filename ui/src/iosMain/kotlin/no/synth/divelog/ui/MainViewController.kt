package no.synth.divelog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.model.units.UnitSystem
import platform.UIKit.UIViewController

/**
 * iOS entry point: hosts the shared Compose UI in a UIViewController for the Swift
 * app to present. Reuses the shared repositories over the native SQLite database.
 * Bluetooth download is Android-only for now; import/export will follow with iOS
 * document pickers.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val container = remember { AppContainer(DriverFactory().createDatabase()) }
    var unitSystem by remember { mutableStateOf(UnitSystem.METRIC) }
    MaterialTheme {
        SynthDivelogApp(
            container = container,
            unitSystem = unitSystem,
            onUnitSystemChange = { unitSystem = it },
            onDownloadClick = {},
        )
    }
}
