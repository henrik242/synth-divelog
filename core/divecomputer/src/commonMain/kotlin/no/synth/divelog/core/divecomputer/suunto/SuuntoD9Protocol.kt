package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Suunto D9 family (HelO2 and relatives). The transport, packet framing and
 * ReadMemory live in [SuuntoD9Link]; this class reads the device identity, walks
 * the profile ring ([SuuntoD9Dump]) and returns one [RawDive] per dive, newest
 * first. [SuuntoD9Parser] turns those blobs into dives. See
 * docs/protocol/suunto-helo2.md.
 */
class SuuntoD9Protocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    val link = SuuntoD9Link(transport, timeoutMs, echoSync = SERIAL_PARAMS.echoSync)

    override fun readDeviceInfo(): DeviceInfo {
        val version = link.readVersion()
        val serial = SuuntoD9Dump.decodeSerial(link.readMemory(SuuntoD9Dump.SERIAL_OFFSET, SuuntoD9Dump.SERIAL_SIZE))
        return deviceInfo(version, serial)
    }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        val version = link.readVersion()
        val serial = SuuntoD9Dump.decodeSerial(link.readMemory(SuuntoD9Dump.SERIAL_OFFSET, SuuntoD9Dump.SERIAL_SIZE))
        listener.onDeviceInfo(deviceInfo(version, serial))

        val header = link.readMemory(SuuntoD9Dump.HEADER_OFFSET, SuuntoD9Dump.HEADER_SIZE)

        // Read the whole profile ring, paging in MAX_PAGE chunks with progress.
        val ringLength = SuuntoD9Dump.RB_PROFILE_END - SuuntoD9Dump.RB_PROFILE_BEGIN
        val ring = ByteArray(ringLength)
        var read = 0
        while (read < ringLength) {
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val n = minOf(SuuntoD9Link.MAX_PAGE, ringLength - read)
            link.readMemory(SuuntoD9Dump.RB_PROFILE_BEGIN + read, n).copyInto(ring, read)
            read += n
            listener.onProgress(read, ringLength)
        }

        val all = SuuntoD9Dump.extract(ring, header)
        // Dives are newest-first; stop at the one already stored for this device.
        val new = if (knownFingerprint == null) all else all.takeWhile { it.fingerprint != knownFingerprint }
        val fresh = if (limit != null && limit > 0) new.take(limit) else new
        listener.onDiveCount(fresh.size)
        fresh.forEachIndexed { index, _ -> listener.onDiveDownloaded(index) }
        return fresh
    }

    private fun deviceInfo(version: ByteArray, serial: String): DeviceInfo {
        val firmware = "${version[1].toInt() and 0xFF}.${version[2].toInt() and 0xFF}.${version[3].toInt() and 0xFF}"
        return DeviceInfo(
            vendor = VENDOR,
            model = SuuntoD9Dump.modelName(version[0].toInt() and 0xFF),
            serial = serial,
            firmware = firmware,
        )
    }

    companion object {
        private const val VENDOR = "Suunto"

        /**
         * 9600 8N1, half-duplex. Verified on hardware: the interface uses the same
         * RTS-high-to-transmit polarity as the old Vyper family, but the proper cable
         * does not echo the sent bytes, so there is nothing to discard. DTR powers the
         * interface. The device replies fast and there is no echo to sync the turnaround
         * on, so the RTS-to-receive switch must land in a narrow window (~6-10 ms after
         * the write); the jitter lets [SuuntoD9Link]'s retries sample different phases.
         */
        val SERIAL_PARAMS = SerialParams(
            baudRate = 9600,
            dataBits = 8,
            parity = Parity.NONE,
            stopBits = 1,
            halfDuplex = true,
            dtr = true,
            rtsTransmitHigh = true,
            discardsEcho = false,
            powerUpMs = 100,
            txSettleMs = 4,
            txJitterMs = 12,
        )
    }
}
