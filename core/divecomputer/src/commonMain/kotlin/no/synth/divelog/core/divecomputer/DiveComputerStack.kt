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

    /** Total number of dives about to be downloaded, once known (e.g. from a manifest). */
    fun onDiveCount(total: Int) {}

    /** [current] of [total] units done; [total] is 0 while still unknown. */
    fun onProgress(current: Int, total: Int) {}

    /**
     * [dive] has been read. [index] is its place in this download's newest-first order
     * (0 is the newest), so a caller can keep what arrived if the download fails later.
     */
    fun onDiveDownloaded(index: Int, dive: RawDive) {}
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
     * newest dive already stored for this device), newest first. [limit], when set,
     * returns only the newest that many dives; for devices that serve dives one at a
     * time this also avoids fetching the rest. Each dive also goes to
     * [DownloadListener.onDiveDownloaded] as soon as it is read, so a download that
     * fails partway still yields the dives read before the failure.
     */
    fun download(
        knownFingerprint: String?,
        listener: DownloadListener = object : DownloadListener {},
        cancel: CancellationSignal = CancellationSignal.NONE,
        limit: Int? = null,
    ): List<RawDive>
}

/**
 * The newest-first dives up to, not including, [knownFingerprint], capped at [limit]
 * when that is positive. Lazy, so a device read behind the sequence stops early.
 */
internal fun <T> Sequence<T>.newestUntil(knownFingerprint: String?, limit: Int?, fingerprint: (T) -> String): Sequence<T> {
    val fresh = if (knownFingerprint == null) this else takeWhile { fingerprint(it) != knownFingerprint }
    return if (limit != null && limit > 0) fresh.take(limit) else fresh
}

internal fun Sequence<RawDive>.newestUntil(knownFingerprint: String?, limit: Int?): Sequence<RawDive> =
    newestUntil(knownFingerprint, limit) { it.fingerprint }

/** Turns a device's raw dive blob into the importable model. One per log format. */
interface DiveLogParser {
    val formatId: String

    fun parse(raw: RawDive): IncomingDive
}

/** Raised when a download is stopped early by [CancellationSignal]. */
class DownloadCancelledException : Exception("Download cancelled")

/** Raised when a device speaks the protocol wrongly or sends unparseable data. */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
