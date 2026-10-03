// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

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
    data class Failed(val message: String) : CaptureState
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
            try {
                state = CaptureState.Connecting(device.name ?: device.address)
                val recording = RecordingTransport(BluetoothDevices.transportFor(device.raw)) {
                    System.currentTimeMillis()
                }
                recording.open()
                try {
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
                } finally {
                    recording.close()
                }
            } catch (e: CancellationException) {
                state = CaptureState.Idle
                throw e
            } catch (e: Exception) {
                state = CaptureState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) state = CaptureState.Idle
    }

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
