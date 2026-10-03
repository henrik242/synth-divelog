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
import no.synth.divelog.core.db.ImportDecision
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPredatorProtocol
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.core.transport.BluetoothDevices
import no.synth.divelog.core.transport.PairedDevice
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.format.Format
import java.io.File

/** A dive whose time range overlaps an existing dive, awaiting a merge decision. */
data class MergeReviewItem(
    val incoming: IncomingDive,
    val existingDiveId: Long,
    val incomingLabel: String,
    val existingLabel: String,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Connecting(val deviceName: String) : DownloadState
    data class Downloading(val bytesRead: Int, val totalBytes: Int) : DownloadState
    data object Importing : DownloadState
    data class Reviewing(
        val items: List<MergeReviewItem>,
        val index: Int,
        val imported: Int,
        val merged: Int,
        val skipped: Int,
    ) : DownloadState
    data class Done(val imported: Int, val merged: Int, val skipped: Int) : DownloadState
    data class Failed(val message: String, val transcriptFile: File?) : DownloadState
}

/**
 * Downloads from the Predator, parses dives and imports them. Duplicates are
 * skipped; a dive that overlaps an existing one (another computer on the same
 * dive) is held for a merge decision before being attached or kept separate.
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
                val rawDives = protocol.download(null, listener, CancellationSignal { !isActive })
                saveTranscript(captureDir, recording)

                state = DownloadState.Importing
                val deviceId = container.devices.getOrCreate(
                    Device(vendor = info.vendor, model = info.model, bluetoothAddress = device.address),
                )
                val parser = PredatorParser()
                var imported = 0
                var skipped = 0
                val review = mutableListOf<MergeReviewItem>()
                for (raw in rawDives) {
                    val incoming = parser.parse(raw).copy(deviceId = deviceId)
                    when (val decision = container.dives.classify(incoming)) {
                        is ImportDecision.Duplicate -> skipped++
                        is ImportDecision.MergeCandidate -> review += reviewItem(incoming, decision.diveId)
                        ImportDecision.NewDive -> { container.dives.importAsNewDive(incoming); imported++ }
                    }
                }
                state = if (review.isEmpty()) {
                    DownloadState.Done(imported, 0, skipped)
                } else {
                    DownloadState.Reviewing(review, 0, imported, 0, skipped)
                }
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

    /** Resolve the current merge review: attach to the existing dive, or keep separate. */
    fun resolveReview(merge: Boolean) {
        val current = state as? DownloadState.Reviewing ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val item = current.items[current.index]
            if (merge) {
                container.dives.attachToDive(item.incoming, item.existingDiveId)
            } else {
                container.dives.importAsNewDive(item.incoming)
            }
            val imported = current.imported + if (merge) 0 else 1
            val merged = current.merged + if (merge) 1 else 0
            val next = current.index + 1
            state = if (next >= current.items.size) {
                DownloadState.Done(imported, merged, current.skipped)
            } else {
                current.copy(index = next, imported = imported, merged = merged)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun reviewItem(incoming: IncomingDive, existingDiveId: Long): MergeReviewItem {
        val existing = container.dives.getDive(existingDiveId)
        return MergeReviewItem(
            incoming = incoming,
            existingDiveId = existingDiveId,
            incomingLabel = "${incoming.number?.let { "#$it " } ?: ""}" +
                "${Format.date(incoming.startEpochSeconds, incoming.utcOffsetSeconds)} " +
                Format.depth(incoming.maxDepthMm, UnitSystem.METRIC),
            existingLabel = existing?.let {
                "${it.number?.let { n -> "#$n " } ?: ""}" +
                    "${Format.date(it.startEpochSeconds, it.utcOffsetSeconds)} " +
                    Format.depth(it.maxDepthMm, UnitSystem.METRIC)
            } ?: "existing dive",
        )
    }

    private fun saveTranscript(dir: File, recording: RecordingTransport): File? = runCatching {
        dir.mkdirs()
        File(dir, "capture-${System.currentTimeMillis()}.transcript.txt").apply {
            writeText(recording.transcript().toText())
        }
    }.getOrNull()
}
