package no.synth.divelog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.download.DownloadService
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.SynthDivelogApp
import no.synth.divelog.ui.download.AndroidSerialPorts
import no.synth.divelog.ui.io.LogbookIo
import no.synth.divelog.ui.settings.AppSettings
import no.synth.divelog.ui.settings.SettingsConnectionMemory
import no.synth.divelog.ui.settings.SettingsStore
import no.synth.divelog.ui.sync.CloudGit
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = AppContainer(DriverFactory(applicationContext).createDatabase())
        val settings = AppSettings(SettingsStore(applicationContext))
        val logbook = LogbookIo(container)
        val cloud = CloudGit(File(applicationContext.filesDir, "cloud").absolutePath)

        setContent {
            no.synth.divelog.ui.SynthTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val prepareDownload = rememberDownloadPermissionRequest(context)
                var dataVersion by remember { mutableIntStateOf(0) }
                var unitSystem by remember { mutableStateOf(settings.unitSystem) }

                // Picks a file and reads it; the shared app runs the import and shows progress.
                val pickImportFile = rememberImportFilePick(context)

                SynthDivelogApp(
                    container = container,
                    unitSystem = unitSystem,
                    onUnitSystemChange = { unitSystem = it; settings.unitSystem = it },
                    serialPorts = remember { AndroidSerialPorts(context.applicationContext) },
                    connectionMemory = remember { SettingsConnectionMemory(settings) },
                    // Request Bluetooth and notification permissions before the picker lists devices.
                    onPrepareDownload = prepareDownload,
                    // Hold the process awake with a foreground service for the length of the download.
                    onDownloadActive = { active ->
                        if (active) {
                            DownloadService.start(context.applicationContext, "Downloading dives")
                        } else {
                            DownloadService.stop(context.applicationContext)
                        }
                    },
                    // Follow the download in the foreground-service notification.
                    onDownloadProgress = { label -> DownloadService.publish(label) },
                    // Save the wire exchange so a real download can be replayed as a test fixture.
                    onRecordTranscript = { transcript ->
                        runCatching {
                            val dir = File(context.filesDir, "captures").apply { mkdirs() }
                            val file = File(dir, "capture-${System.currentTimeMillis()}.transcript.txt")
                            file.writeText(transcript)
                            android.util.Log.i("SynthDivelog", "Saved download transcript to ${file.absolutePath}")
                        }
                    },
                    onDownloaded = { dataVersion++ },
                    onPickImportFile = pickImportFile,
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
                    initialCloudEmail = settings.cloudEmail,
                    initialCloudPassword = settings.cloudPassword,
                    onCloudConfigChange = { email, pass ->
                        settings.cloudEmail = email
                        settings.cloudPassword = pass
                    },
                    cloud = cloud,
                    onCloudPush = { email, pass ->
                        scope.launch(Dispatchers.IO) {
                            val message = runCatching {
                                cloud.push(email, pass, logbook.exportCloudTree())
                                "Pushed to the cloud"
                            }.getOrElse { "Push failed: ${it.message ?: it::class.simpleName}" }
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onExit = { this@MainActivity.finish() },
                    dataVersion = dataVersion,
                )
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

/**
 * Builds a suspend action that opens the system document picker and returns the chosen
 * file's text, or null if the user cancelled. The launcher must be created in composition,
 * so the pending result is bridged to the coroutine through a [CompletableDeferred]. The
 * file is read off the main thread.
 */
@Composable
private fun rememberImportFilePick(context: Context): suspend () -> ByteArray? {
    val pending = remember { mutableStateOf<CompletableDeferred<android.net.Uri?>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pending.value?.complete(uri)
        pending.value = null
    }
    return {
        val deferred = CompletableDeferred<android.net.Uri?>()
        pending.value = deferred
        launcher.launch(arrayOf("*/*"))
        val uri = deferred.await()
        if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }
    }
}

/**
 * Builds a suspend action that requests the permissions the download needs and resolves
 * once the user has responded. Bluetooth-connect gates the paired-device list and the
 * RFCOMM connection; the notification permission lets the foreground service show its
 * notification. USB-only downloads work without either, so a denial is not fatal.
 */
@Composable
private fun rememberDownloadPermissionRequest(context: Context): suspend () -> Unit {
    val pending = remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pending.value?.complete(Unit)
        pending.value = null
    }
    return {
        val missing = downloadPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            val deferred = CompletableDeferred<Unit>()
            pending.value = deferred
            launcher.launch(missing.toTypedArray())
            deferred.await()
        }
    }
}

/** Bluetooth is required to reach a paired computer; the notification permission (API 33+) backs the service. */
private fun downloadPermissions(): Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
