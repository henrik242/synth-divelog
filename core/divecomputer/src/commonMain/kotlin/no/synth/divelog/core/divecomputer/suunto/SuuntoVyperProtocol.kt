package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.newestUntil
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Downloads from the old Suunto Vyper family over a wired serial line: the Zoop
 * and its 2400 8O1 half-duplex siblings. Reads the device header for the model and
 * the profile write pointer, dumps the profile ring buffer, then splits it into
 * per-dive blobs ([SuuntoVyperDump]).
 *
 * The caller must open the [Transport] with [SERIAL_PARAMS] so the half-duplex
 * RTS/DTR timing and odd parity are in force; this class only speaks the read
 * command.
 */
class SuuntoVyperProtocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    private val memory = SuuntoVyperMemory(transport, timeoutMs)

    override fun readDeviceInfo(): DeviceInfo {
        // Model, firmware, then a serial of four base-100 digits.
        val h = memory.readPage(SuuntoVyperDump.MODEL_OFFSET, SuuntoVyperDump.DEVINFO_SIZE)
        val serial = (2 until 6).fold(0L) { acc, i -> acc * 100 + (h[i].toInt() and 0xFF) }
        return DeviceInfo(
            vendor = VENDOR,
            model = SuuntoVyperDump.modelName(h[0].toInt() and 0xFF),
            serial = serial.toString(),
            firmware = (h[1].toInt() and 0xFF).toString(),
        )
    }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        listener.onDeviceInfo(readDeviceInfo())

        val pointer = memory.readU16BE(SuuntoVyperDump.POINTER_OFFSET)
        val ringLength = SuuntoVyperDump.RING_END - SuuntoVyperDump.RING_BEGIN
        val ring = memory.readRange(
            address = SuuntoVyperDump.RING_BEGIN,
            length = ringLength,
            onProgress = { read, total -> listener.onProgress(read, total) },
            cancel = cancel,
        )

        val fresh = SuuntoVyperDump.extract(ring, pointer).asSequence().newestUntil(knownFingerprint, limit).toList()
        listener.onDiveCount(fresh.size)
        fresh.forEachIndexed { index, dive -> listener.onDiveDownloaded(index, dive) }
        return fresh
    }

    companion object {
        private const val VENDOR = "Suunto"

        /**
         * 2400 8O1, half-duplex, timed as libdivecomputer does: DTR powers the
         * interface and stays set, 500 ms quiet before each command, RTS held
         * 200 ms after sending, then dropped to receive. The Suunto cable does not
         * echo. The computer must be in its TR-PC (transfer) mode.
         */
        val SERIAL_PARAMS = SerialParams(
            baudRate = 2400,
            dataBits = 8,
            parity = Parity.ODD,
            stopBits = 1,
            halfDuplex = true,
            dtr = true,
            discardsEcho = false,
            txIdleMs = 500,
            powerUpMs = 100,
            txSettleMs = 200,
        )
    }
}
