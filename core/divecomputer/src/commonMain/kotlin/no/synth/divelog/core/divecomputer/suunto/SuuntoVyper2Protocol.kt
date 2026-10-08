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
 * Suunto Vyper2 family (HelO2 and relatives). The transport, packet framing and
 * ReadMemory live in [SuuntoVyper2Link]; this class reads the device identity, walks
 * the profile ring ([SuuntoVyper2Dump]) and returns one [RawDive] per dive, newest
 * first. [SuuntoVyper2Parser] turns those blobs into dives. See
 * docs/protocol/suunto-helo2.md.
 */
class SuuntoVyper2Protocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    val link = SuuntoVyper2Link(transport, timeoutMs)

    override fun readDeviceInfo(): DeviceInfo {
        val version = link.readVersion()
        val serial = SuuntoVyper2Dump.decodeSerial(link.readMemory(SuuntoVyper2Dump.SERIAL_OFFSET, SuuntoVyper2Dump.SERIAL_SIZE))
        return deviceInfo(version, serial)
    }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        val version = link.readVersion()
        val serial = SuuntoVyper2Dump.decodeSerial(link.readMemory(SuuntoVyper2Dump.SERIAL_OFFSET, SuuntoVyper2Dump.SERIAL_SIZE))
        listener.onDeviceInfo(deviceInfo(version, serial))

        val header = link.readMemory(SuuntoVyper2Dump.HEADER_OFFSET, SuuntoVyper2Dump.HEADER_SIZE)

        // Walk the dives newest-first and read the ring backward from `end` only as far as
        // the walk reaches, a page at a time, so an incremental download stops after the
        // new dives instead of reading the whole ring.
        val used = SuuntoVyper2Dump.usedBytes(header)
        val end = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
        val ring = ByteArray(SuuntoVyper2Dump.ringSize)
        var fetched = 0 // bytes read so far, going back from end
        var walked = 0 // bytes the walk has consumed, going back from end
        val fresh = ArrayList<RawDive>()
        SuuntoVyper2Dump.walk(
            header,
            read = { start, size ->
                walked += size
                while (fetched < walked) {
                    if (cancel.isCancelled()) throw DownloadCancelledException()
                    // The page ends where the fetched span begins and never crosses the wrap.
                    var pageEnd = end - fetched
                    if (pageEnd <= SuuntoVyper2Dump.RB_PROFILE_BEGIN) pageEnd += SuuntoVyper2Dump.ringSize
                    val n = minOf(SuuntoVyper2Link.MAX_PAGE, pageEnd - SuuntoVyper2Dump.RB_PROFILE_BEGIN, used - fetched)
                    link.readMemory(pageEnd - n, n).copyInto(ring, pageEnd - n - SuuntoVyper2Dump.RB_PROFILE_BEGIN)
                    fetched += n
                    listener.onProgress(fetched, used)
                }
                SuuntoVyper2Dump.recordAt(start, size, ring)
            },
        ) { dive ->
            if (dive.fingerprint == knownFingerprint) return@walk false
            fresh += dive
            listener.onDiveDownloaded(fresh.size - 1)
            limit == null || limit <= 0 || fresh.size < limit
        }
        if (used > 0) listener.onProgress(used, used)
        return fresh
    }

    private fun deviceInfo(version: ByteArray, serial: String): DeviceInfo {
        val firmware = "${version[1].toInt() and 0xFF}.${version[2].toInt() and 0xFF}.${version[3].toInt() and 0xFF}"
        return DeviceInfo(
            vendor = VENDOR,
            model = SuuntoVyper2Dump.modelName(version[0].toInt() and 0xFF),
            serial = serial,
            firmware = firmware,
        )
    }

    companion object {
        private const val VENDOR = "Suunto"

        /**
         * 9600 8N1, half-duplex, DTR powers the interface, RTS high to transmit. The proper
         * cable does not echo. Measured on a HelO2: the device ignores a command that comes
         * less than ~500 ms after its previous reply (every read failed at 450 ms, none at
         * 500 ms or more), so the line is kept quiet for 600 ms before each command. The
         * reply starts ~20 ms after the command ends, so RTS must switch to receive as soon
         * as the command is out (the transport's drain wait, no extra settle): an added
         * 10 ms garbled the start of about one reply in four.
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
            txIdleMs = 600,
            powerUpMs = 100,
        )
    }
}
