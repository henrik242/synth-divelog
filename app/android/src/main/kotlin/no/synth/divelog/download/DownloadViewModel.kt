package no.synth.divelog.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.synth.divelog.core.db.DiveRepository
import no.synth.divelog.core.db.ImportResult
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPredatorProtocol
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.transport.BluetoothDevices
import no.synth.divelog.core.transport.PairedDevice
import no.synth.divelog.ui.AppContainer
import java.io.File

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Connecting(val deviceName: String) : DownloadState
    data class Downloading(val bytesRead: Int, val totalBytes: Int) : DownloadState
    data object Importing : DownloadState
    data class Done(val imported: Int, val skipped: Int) : DownloadState
    data class Failed(val message: String, val transcriptFile: File?) : DownloadState
}

/**
 * Downloads from the Predator, parses each dive and imports it into the
 * database. Duplicates already stored are skipped. The raw serial exchange is
 * also saved as a transcript for debugging.
 */
class DownloadViewModel(private val container: AppContainer) : ViewModel() {
    var state: DownloadState by mutableStateOf(DownloadState.Idle)
        private set

    private var job: Job? = null

    fun start(device: PairedDevice, captureDir: File) {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            var info = DeviceInfo(vendor = "Shearwater", model = "Predator")
            val recording = RecordingTransport(BluetoothDevices.transportFor(device.raw)) {
                System.currentTimeMillis()
            }
            try {
                state = DownloadState.Connecting(device.name ?: device.address)
                recording.open()
                val protocol = ShearwaterPredatorProtocol(recording)
                val listener = object : DownloadListener {
                    override fun onDeviceInfo(i: DeviceInfo) { info = i }
                    override fun onProgress(current: Int, total: Int) {
                        state = DownloadState.Downloading(current, total)
                    }
                }
                val cancel = CancellationSignal { !isActive }
                val rawDives = protocol.download(knownFingerprint = null, listener = listener, cancel = cancel)
                saveTranscript(captureDir, recording)

                state = DownloadState.Importing
                val deviceId = container.devices.getOrCreate(
                    Device(vendor = info.vendor, model = info.model, bluetoothAddress = device.address),
                )
                val parser = PredatorParser()
                var imported = 0
                var skipped = 0
                for (raw in rawDives) {
                    val incoming = parser.parse(raw).copy(deviceId = deviceId)
                    when (container.dives.import(incoming) { false }) {
                        is ImportResult.SkippedDuplicate -> skipped++
                        else -> imported++
                    }
                }
                state = DownloadState.Done(imported, skipped)
            } catch (e: CancellationException) {
                state = DownloadState.Idle
                throw e
            } catch (e: Exception) {
                state = DownloadState.Failed(e.message ?: e.toString(), saveTranscript(captureDir, recording))
            } finally {
                recording.close()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun saveTranscript(dir: File, recording: RecordingTransport): File? = runCatching {
        dir.mkdirs()
        File(dir, "capture-${System.currentTimeMillis()}.transcript.txt").apply {
            writeText(recording.transcript().toText())
        }
    }.getOrNull()
}
