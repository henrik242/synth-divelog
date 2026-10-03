package no.synth.divelog.capture

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
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPredatorProtocol
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.transport.BluetoothDevices
import no.synth.divelog.core.transport.PairedDevice
import java.io.File

/** Outcome files and summary of a completed capture. */
data class CaptureSummary(
    val transcriptFile: File,
    val summaryFile: File,
    val model: String,
    val diveCount: Int,
    val fingerprints: List<String>,
)

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Connecting(val deviceName: String) : CaptureState
    data class Downloading(val bytesRead: Int, val totalBytes: Int, val dives: Int) : CaptureState
    data class Done(val summary: CaptureSummary) : CaptureState

    /** Download failed; [transcriptFile] holds whatever exchange was recorded, for diagnosis. */
    data class Failed(val message: String, val transcriptFile: File?) : CaptureState
}

/**
 * Drives a debug download: connect, pull the raw memory, and save the full
 * serial exchange as a transcript the user can share off the phone. Those
 * transcripts become the protocol/parser test fixtures. Runs in the view-model
 * scope so it survives rotation and can be cancelled.
 */
class CaptureViewModel : ViewModel() {
    var state: CaptureState by mutableStateOf(CaptureState.Idle)
        private set

    private var job: Job? = null

    fun start(device: PairedDevice, captureDir: File) {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            var deviceInfo = DeviceInfo(vendor = "Shearwater", model = "Predator")
            var diveCount = 0
            val recording = RecordingTransport(BluetoothDevices.transportFor(device.raw)) {
                System.currentTimeMillis()
            }
            try {
                state = CaptureState.Connecting(device.name ?: device.address)
                recording.open()
                val protocol = ShearwaterPredatorProtocol(recording)
                val listener = object : DownloadListener {
                    override fun onDeviceInfo(info: DeviceInfo) {
                        deviceInfo = info
                    }

                    override fun onProgress(current: Int, total: Int) {
                        state = CaptureState.Downloading(current, total, diveCount)
                    }

                    override fun onDiveDownloaded(index: Int) {
                        diveCount = index + 1
                    }
                }
                val cancel = CancellationSignal { !isActive }
                val dives = protocol.download(knownFingerprint = null, listener = listener, cancel = cancel)
                val summary = saveCapture(captureDir, recording.transcript().toText(), deviceInfo, dives.map { it.fingerprint })
                state = CaptureState.Done(summary)
            } catch (e: CancellationException) {
                saveTranscript(captureDir, recording) // keep the partial exchange even on cancel
                state = CaptureState.Idle
                throw e
            } catch (e: Exception) {
                val partial = saveTranscript(captureDir, recording)
                state = CaptureState.Failed(e.message ?: e.toString(), partial)
            } finally {
                recording.close()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) state = CaptureState.Idle
    }

    /** Write just the recorded exchange; used when a download fails or is cancelled. */
    private fun saveTranscript(dir: File, recording: RecordingTransport): File? = runCatching {
        dir.mkdirs()
        File(dir, "capture-${System.currentTimeMillis()}.transcript.txt").apply {
            writeText(recording.transcript().toText())
        }
    }.getOrNull()

    private fun saveCapture(
        dir: File,
        transcript: String,
        info: DeviceInfo,
        fingerprints: List<String>,
    ): CaptureSummary {
        dir.mkdirs()
        val stamp = System.currentTimeMillis()
        val transcriptFile = File(dir, "capture-$stamp.transcript.txt")
        transcriptFile.writeText(transcript)
        val summaryFile = File(dir, "capture-$stamp.summary.txt")
        summaryFile.writeText(
            buildString {
                appendLine("vendor=${info.vendor}")
                appendLine("model=${info.model}")
                appendLine("dives=${fingerprints.size}")
                appendLine("fingerprints=${fingerprints.joinToString(",")}")
            },
        )
        return CaptureSummary(transcriptFile, summaryFile, info.model, fingerprints.size, fingerprints)
    }
}
