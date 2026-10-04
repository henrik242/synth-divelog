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
            no.synth.divelog.ui.SynthTheme {
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
                            val message = withContext(Dispatchers.IO) {
                                runCatching {
                                    val text = context.contentResolver.openInputStream(uri)
                                        ?.bufferedReader()?.use { it.readText() }
                                        ?: return@runCatching "Could not read the file"
                                    val format = LogbookIo.detect(text)
                                        ?: return@runCatching "Unrecognized file (expected Subsurface XML or UDDF)"
                                    val counts = logbook.import(format, text)
                                    "Imported ${counts.imported}, skipped ${counts.skipped} (${format.displayName})"
                                }.getOrElse { "Import failed: ${it.message ?: it::class.simpleName}" }
                            }
                            dataVersion++
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
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
                        onReparse = {
                            scope.launch(Dispatchers.IO) {
                                val count = logbook.reparseAll()
                                dataVersion++
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast
                                        .makeText(context, "Re-parsed $count dives", android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
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
                            scope.launch(Dispatchers.IO) {
                                val message = runCatching {
                                    val text = CloudSync.pull(url, user, pass)
                                    val format = LogbookIo.detect(text)
                                        ?: return@runCatching "Downloaded, but not a recognized dive-log file"
                                    val counts = logbook.import(format, text)
                                    "Pulled: imported ${counts.imported}, skipped ${counts.skipped}"
                                }.getOrElse { "Pull failed: ${it.message ?: it::class.simpleName}" }
                                dataVersion++
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onCloudPush = { url, user, pass ->
                            scope.launch(Dispatchers.IO) {
                                val message = runCatching {
                                    val format = LogbookIo.formats().first { it.id == "subsurface-xml" }
                                    CloudSync.push(url, user, pass, logbook.exportAll(format))
                                    "Pushed to the cloud"
                                }.getOrElse { "Push failed: ${it.message ?: it::class.simpleName}" }
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                }
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
