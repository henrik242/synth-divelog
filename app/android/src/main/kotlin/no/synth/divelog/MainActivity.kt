package no.synth.divelog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.logbook.download.AndroidSerialPorts
import no.synth.divelog.core.logbook.download.DiveComputerType
import no.synth.divelog.core.logbook.download.SimulatedSerialPorts
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
            val saveExport = rememberExportSave(this)
            val prepareDownload = rememberDownloadPermissionRequest(this)
            val backupFolders = rememberBackupFolders(applicationContext)
            val hooks = remember {
                PlatformHooks(
                    serialPorts = AndroidSerialPorts(applicationContext).let { if (simulatorRequested()) SimulatedSerialPorts(it) else it },
                    pickImportFile = pickImportFile,
                    saveExport = saveExport,
                    shareExport = ::shareExport,
                    backupFolders = backupFolders,
                    prepareDownload = prepareDownload,
                    downloadHint = ::downloadHint,
                    // A foreground service holds the process awake and shows the progress;
                    // the screen stays on too, so the download can be watched.
                    onDownloadActive = { active ->
                        if (active) {
                            DownloadService.start(applicationContext, "Downloading dives")
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            DownloadService.stop(applicationContext)
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    },
                    onDownloadProgress = { DownloadService.publish(it) },
                    recordTranscript = ::saveTranscript,
                    setCrashReporting = { FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = it },
                )
            }
            SynthDivelogApp(services, hooks)
        }
    }

    /**
     * A debuggable build started with `--ez simulator true` offers a simulated dive computer:
     * `adb shell am start -n no.synth.divelog.debug/no.synth.divelog.MainActivity --ez simulator true`
     */
    private fun simulatorRequested(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("simulator", false)

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
        startActivity(Intent.createChooser(intent, export.name))
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
 * Bridges an activity result to a coroutine: the returned action launches [contract] and
 * suspends until its result. The launcher must be created in composition, so the pending
 * result goes through a [CompletableDeferred]. A call while another is waiting returns null
 * at once. A result nobody waits for (the activity was recreated meanwhile, cancelling the
 * caller) goes to [onUnclaimed].
 */
@Composable
internal fun <I, O> rememberActivityResult(
    contract: ActivityResultContract<I, O>,
    onUnclaimed: (O) -> Unit = {},
): suspend (I) -> O? {
    val pending = remember { mutableStateOf<CompletableDeferred<O>?>(null) }
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        val waiting = pending.value
        pending.value = null
        if (waiting != null) waiting.complete(result) else onUnclaimed(result)
    }
    return { input ->
        if (pending.value != null) {
            null
        } else {
            val deferred = CompletableDeferred<O>()
            pending.value = deferred
            try {
                launcher.launch(input)
                deferred.await()
            } finally {
                if (pending.value === deferred) pending.value = null
            }
        }
    }
}

/**
 * Builds a suspend action that opens the system document picker and returns the chosen
 * file's bytes, or null if the user cancelled. The file is read off the main thread.
 */
@Composable
private fun rememberImportFilePick(context: Context): suspend () -> ByteArray? {
    val pick = rememberActivityResult(ActivityResultContracts.OpenDocument())
    return {
        pick(arrayOf("*/*"))?.let { uri ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }
    }
}

/**
 * Builds a suspend action that opens the system save dialog (Downloads, Drive, ...) with the
 * export's name and writes it to the chosen document. Returns false if the user cancelled
 * or a save is already waiting on its dialog.
 */
@Composable
private fun rememberExportSave(context: Context): suspend (ExportFile) -> Boolean {
    val scope = rememberCoroutineScope()
    // A generic type keeps the suggested name as is; a specific one lets the provider append
    // its own extension (synth-divelog.uddf.xml).
    val pick = rememberActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        // The activity was recreated while the dialog was open and the export went with it:
        // drop the empty document the dialog made, so the user just exports again.
        if (uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                    .onFailure { Log.w("SynthDivelog", "Dropping an interrupted export failed", it) }
            }
        }
    }
    return { export ->
        val uri = pick(export.name)
        if (uri != null) writeDocument(context, uri, export.bytes)
        uri != null
    }
}

private suspend fun writeDocument(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
    // "w", not "wt": the document is new and empty, and some providers reject "wt".
    val out = requireNotNull(context.contentResolver.openOutputStream(uri, "w")) { "Cannot write $uri" }
    out.use { it.write(bytes) }
}

/**
 * Builds a suspend action that requests the permissions the download needs and resolves
 * once the user has responded. Bluetooth-connect gates the paired-device list and the
 * RFCOMM and GATT connections, Bluetooth-scan the search for LE computers; the
 * notification permission lets the foreground service show its notification. USB-only
 * downloads work without any of them, so a denial is not fatal.
 */
@Composable
private fun rememberDownloadPermissionRequest(context: Context): suspend () -> Unit {
    val request = rememberActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    return {
        val missing = downloadPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) request(missing.toTypedArray())
    }
}

/** What a phone adds to the shared connection note: the cable goes through a USB OTG adapter, and pairing. */
private fun downloadHint(type: DiveComputerType): String? = when (type) {
    DiveComputerType.SUUNTO_VYPER, DiveComputerType.SUUNTO_VYPER2 -> "A phone takes it through a USB OTG adapter."
    DiveComputerType.SHEARWATER_PETREL, DiveComputerType.SHEARWATER_PREDATOR -> null
    DiveComputerType.SHEARWATER ->
        "Android may ask to pair. A Petrel 2, Perdix or NERD can also be paired in Android settings to use Bluetooth Classic."
}

/**
 * Bluetooth connect is required to reach a paired computer and scan to find a Bluetooth LE
 * one; the notification permission backs the service.
 */
private fun downloadPermissions(): Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
