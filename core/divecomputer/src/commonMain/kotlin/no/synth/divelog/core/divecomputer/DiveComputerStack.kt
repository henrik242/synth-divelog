package no.synth.divelog.core.divecomputer

import no.synth.divelog.core.model.IncomingDive

/** Identity and firmware a device reports about itself. */
data class DeviceInfo(
    val vendor: String,
    val model: String,
    val serial: String? = null,
    val firmware: String? = null,
)

/**
 * One dive as pulled from a device, still in the device's own byte layout. The
 * raw bytes are kept verbatim so a record can be re-parsed after a parser fix;
 * [fingerprint] uniquely identifies the dive on that device for duplicate
 * detection.
 */
class RawDive(
    val fingerprint: String,
    val data: ByteArray,
    val formatId: String,
)

/** Progress and running state of a download, for the UI. */
interface DownloadListener {
    fun onDeviceInfo(info: DeviceInfo) {}

    /** [current] of [total] units done; [total] is 0 while still unknown. */
    fun onProgress(current: Int, total: Int) {}

    fun onDiveDownloaded(index: Int) {}
}

/** Polled by long operations so the user can cancel a download. */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        val NONE = CancellationSignal { false }
    }
}

/**
 * Talks to one family of dive computers over a transport: reads device info and
 * pulls raw dive blobs. Knows framing and the command exchange, not the log
 * layout (that is the parser's job).
 */
interface DiveComputerProtocol {
    fun readDeviceInfo(): DeviceInfo

    /**
     * Download dives, skipping anything at or before [knownFingerprint] (the
     * newest dive already stored for this device), newest first.
     */
    fun download(
        knownFingerprint: String?,
        listener: DownloadListener = object : DownloadListener {},
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): List<RawDive>
}

/** Turns a device's raw dive blob into the importable model. One per log format. */
interface DiveLogParser {
    val formatId: String

    fun parse(raw: RawDive): IncomingDive
}

/** Raised when a download is stopped early by [CancellationSignal]. */
class DownloadCancelledException : Exception("Download cancelled")

/** Raised when a device speaks the protocol wrongly or sends unparseable data. */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
