package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Suunto D9 family (HelO2 and relatives). The transport, packet framing and
 * ReadMemory are implemented and tested ([SuuntoD9Link]); the dive directory and
 * the richer trimix/deco profile parser are not written yet, so [download] reads
 * nothing and reports as much. This is the scaffolding the HelO2 support will grow
 * from. See docs/protocol/suunto-helo2.md.
 */
class SuuntoD9Protocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    val link = SuuntoD9Link(transport, timeoutMs)

    override fun readDeviceInfo(): DeviceInfo {
        val version = link.readVersion()
        val firmware = "${version[1].toInt() and 0xFF}.${version[2].toInt() and 0xFF}.${version[3].toInt() and 0xFF}"
        return DeviceInfo(vendor = VENDOR, model = "HelO2", firmware = firmware)
    }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        throw ProtocolException(
            "Suunto D9/HelO2 download is not implemented yet; the ReadMemory command " +
                "is in place but the dive directory and profile parser are pending hardware.",
        )
    }

    companion object {
        private const val VENDOR = "Suunto"

        /**
         * 9600 8N1, half-duplex with inverted RTS: the line drives RTS low to transmit
         * and high to receive (the opposite of the old Vyper family), and its RTS
         * direction control leaves no echo to discard. DTR powers the interface; give
         * it time to settle after each write before the reply.
         */
        val SERIAL_PARAMS = SerialParams(
            baudRate = 9600,
            dataBits = 8,
            parity = Parity.NONE,
            stopBits = 1,
            halfDuplex = true,
            dtr = true,
            rtsTransmitHigh = false,
            discardsEcho = false,
            powerUpMs = 100,
            txSettleMs = 50,
        )
    }
}
