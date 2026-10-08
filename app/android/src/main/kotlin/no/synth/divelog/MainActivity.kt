package no.synth.divelog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.synth.divelog.core.logbook.download.AndroidSerialPorts
import no.synth.divelog.core.logbook.io.ExportFile
import no.synth.divelog.download.DownloadService
import no.synth.divelog.ui.PlatformHooks
import no.synth.divelog.ui.SynthDivelogApp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val services = (application as SynthDivelogApplication).services

        setContent {
            val pickImportFile = rememberImportFilePick(this)
            val prepareDownload = rememberDownloadPermissionRequest(this)
            val hooks = remember {
                PlatformHooks(
                    serialPorts = AndroidSerialPorts(applicationContext),
                    pickImportFile = pickImportFile,
                    saveExport = ::shareExport,
                    prepareDownload = prepareDownload,
                    // A foreground service holds the process awake and shows the progress.
                    onDownloadActive = { active ->
                        if (active) DownloadService.start(applicationContext, "Downloading dives") else DownloadService.stop(applicationContext)
                    },
                    onDownloadProgress = { DownloadService.publish(it) },
                    recordTranscript = ::saveTranscript,
                )
            }
            SynthDivelogApp(services, hooks)
        }
    }

    /** Writes the export to app storage and offers it to the share sheet. */
    private suspend fun shareExport(export: ExportFile) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(filesDir, "exports").apply { mkdirs() }
            File(dir, export.name).apply { writeBytes(export.bytes) }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = export.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export logbook"))
    }

    /** Saves a download's wire exchange so it can be replayed as a test fixture. */
    private fun saveTranscript(transcript: String) {
        runCatching {
            val dir = File(filesDir, "captures").apply { mkdirs() }
            val file = File(dir, "capture-${System.currentTimeMillis()}.transcript.txt")
            file.writeText(transcript)
            Log.i("SynthDivelog", "Saved download transcript to ${file.absolutePath}")
        }
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
