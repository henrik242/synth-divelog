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
        val model = SuuntoVyperDump.modelName(memory.readByte(SuuntoVyperDump.MODEL_OFFSET))
        return DeviceInfo(vendor = VENDOR, model = model)
    }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        val modelByte = memory.readByte(SuuntoVyperDump.MODEL_OFFSET)
        val model = SuuntoVyperDump.modelName(modelByte)
        listener.onDeviceInfo(DeviceInfo(vendor = VENDOR, model = model))

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
         * 2400 8O1, half-duplex. DTR powers the interface and stays set; RTS flips
         * the line direction with a settle delay each way; the line echoes sent
         * bytes, which the transport discards.
         */
        val SERIAL_PARAMS = SerialParams(
            baudRate = 2400,
            dataBits = 8,
            parity = Parity.ODD,
            stopBits = 1,
            halfDuplex = true,
            dtr = true,
            txSettleMs = 200,
            rxSettleMs = 400,
        )
    }
}
