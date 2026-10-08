package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.newestUntil
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Downloads from a Shearwater Predator (and the Petrel 1, which uses the same
 * older log format). Reads the whole profile memory as one uncompressed dump,
 * then extracts the individual dives.
 */
class ShearwaterPredatorProtocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    private val link = ShearwaterLink(transport, timeoutMs)
    private val memory = ShearwaterMemory(link)

    override fun readDeviceInfo(): DeviceInfo {
        // The older models carry their model byte in the memory dump; identity is
        // resolved there rather than from a separate query.
        return DeviceInfo(vendor = VENDOR, model = "Predator")
    }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        val dump = memory.read(
            baseAddress = BASE_ADDRESS,
            size = MEMORY_SIZE,
            onProgress = { read, total -> listener.onProgress(read, total) },
            cancel = cancel,
        )

        listener.onDeviceInfo(DeviceInfo(vendor = VENDOR, model = "Predator"))

        // The full dump is already read in one go, so a limit only trims the result.
        val fresh = PredatorDump.extract(dump).asSequence().newestUntil(knownFingerprint, limit).toList()
        fresh.forEachIndexed { index, dive -> listener.onDiveDownloaded(index, dive) }
        return fresh
    }

    companion object {
        private const val VENDOR = "Shearwater"
        private const val BASE_ADDRESS = 0xDD000000L
        private const val MEMORY_SIZE = 0x20080
    }
}
