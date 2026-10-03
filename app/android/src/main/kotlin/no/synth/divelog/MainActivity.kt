package no.synth.divelog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.download.DownloadScreen
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.SynthDivelogApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = AppContainer(DriverFactory(applicationContext).createDatabase())
        val settings = SettingsStore(applicationContext)

        setContent {
            MaterialTheme {
                var showDownload by remember { mutableStateOf(false) }
                var dataVersion by remember { mutableIntStateOf(0) }
                var unitSystem by remember { mutableStateOf(settings.unitSystem) }

                if (showDownload) {
                    DownloadScreen(
                        container = container,
                        onImported = { showDownload = false; dataVersion++ },
                        onBack = { showDownload = false },
                    )
                } else {
                    SynthDivelogApp(
                        container = container,
                        unitSystem = unitSystem,
                        onUnitSystemChange = { unitSystem = it; settings.unitSystem = it },
                        onDownloadClick = { showDownload = true },
                        dataVersion = dataVersion,
                    )
                }
            }
        }
    }
}
