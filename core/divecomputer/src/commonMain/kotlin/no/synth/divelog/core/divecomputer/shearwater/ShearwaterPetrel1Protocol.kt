package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Downloads from a Shearwater Petrel 1. Unlike the Predator, the Petrel keeps a
 * manifest of dives and serves each dive individually, compressed ([ShearwaterLogbook]).
 * The dive log records themselves use the same older format the Predator parser reads.
 */
class ShearwaterPetrel1Protocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    private val link = ShearwaterLink(transport, timeoutMs)
    private val logbook = ShearwaterLogbook(ShearwaterMemory(link))

    override fun readDeviceInfo(): DeviceInfo = DeviceInfo(vendor = SHEARWATER, model = "Petrel")

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        listener.onDeviceInfo(readDeviceInfo())
        try {
            return logbook.download(
                ShearwaterLogbook.PREDATOR_LIKE_BASE,
                PredatorParser.PETREL_FORMAT_ID,
                knownFingerprint,
                listener,
                cancel,
                limit,
            )
        } finally {
            // Without it the Petrel shows "bluetooth error: disconnect" (verified on a Petrel 1).
            link.shutdown()
        }
    }
}
