package no.synth.divelog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.download.DownloadScreen
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.SynthDivelogApp
import no.synth.divelog.ui.io.LogbookIo
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = AppContainer(DriverFactory(applicationContext).createDatabase())
        val settings = SettingsStore(applicationContext)
        val logbook = LogbookIo(container)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var showDownload by remember { mutableStateOf(false) }
                var dataVersion by remember { mutableIntStateOf(0) }
                var unitSystem by remember { mutableStateOf(settings.unitSystem) }

                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val text = context.contentResolver.openInputStream(uri)
                                    ?.bufferedReader()?.use { it.readText() } ?: return@withContext
                                LogbookIo.detect(text)?.let { logbook.import(it, text) }
                            }
                            dataVersion++
                        }
                    }
                }

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
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onExport = { formatId ->
                            scope.launch(Dispatchers.IO) {
                                val format = LogbookIo.formats().first { it.id == formatId }
                                val text = logbook.exportAll(format)
                                shareExport(context, formatId, text)
                            }
                        },
                        dataVersion = dataVersion,
                    )
                }
            }
        }
    }

    private fun shareExport(context: android.content.Context, formatId: String, text: String) {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val ext = if (formatId == "uddf") "uddf" else "xml"
        val file = File(dir, "synth-divelog.$ext").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export logbook").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
