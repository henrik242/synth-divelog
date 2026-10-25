package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.u32be

/**
 * Downloads from the Petrel family as libdivecomputer's `shearwater_petrel.c` reads it: the
 * Petrel 2 and the Peregrine. It is the Petrel 1's manifest download ([ShearwaterPetrel1Protocol])
 * with three additions: the serial and firmware are read by identifier, the log format
 * identifier says where the dives live and which layout they use, and the session ends with
 * a shutdown command so the device leaves its Bluetooth screen cleanly.
 *
 * With [packets] the [transport] is a Bluetooth LE GATT link, which [ShearwaterBlePackets]
 * turns into the SLIP stream the command layer speaks; without, it is that stream already
 * (Bluetooth Classic). [model] is what the device is reported as.
 */
class ShearwaterPetrelFamilyProtocol private constructor(
    transport: Transport,
    private val model: String,
    packets: Boolean,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    private val link = ShearwaterLink(if (packets) ShearwaterBlePackets(transport) else transport, timeoutMs)
    private val logbook = ShearwaterLogbook(ShearwaterMemory(link))
    private var info: DeviceInfo? = null

    override fun readDeviceInfo(): DeviceInfo = info ?: DeviceInfo(
        vendor = SHEARWATER,
        model = model,
        serial = serial(link.readIdentifier(ID_SERIAL)),
        firmware = firmware(link.readIdentifier(ID_FIRMWARE)),
    ).also { info = it }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        try {
            listener.onDeviceInfo(readDeviceInfo())
            val format = logFormat(link.readIdentifier(ID_LOG_UPLOAD))
            return logbook.download(format.diveBase, format.formatId, knownFingerprint, listener, cancel, limit)
        } finally {
            // Sent after a failed setup too, while the link may still be up.
            link.shutdown()
        }
    }

    /** Where a log format's dives live, and the format id their raw blobs carry. */
    internal data class LogFormat(val diveBase: Long, val formatId: String)

    companion object {
        private const val ID_SERIAL = 0x8010
        private const val ID_FIRMWARE = 0x8011
        private const val ID_LOG_UPLOAD = 0x8021

        /** The Peregrine: Bluetooth LE only. */
        fun peregrine(transport: Transport) = ShearwaterPetrelFamilyProtocol(transport, "Peregrine", packets = true)

        /**
         * The Petrel 2, over Bluetooth Classic or LE. A packet link is the one that knows its
         * write size ([Transport.maxWriteSize] is null for a byte stream), so [transport] must
         * be open; a replayed LE capture carries its size in the transcript.
         */
        fun petrel2(transport: Transport) =
            ShearwaterPetrelFamilyProtocol(transport, "Petrel 2", packets = transport.maxWriteSize != null)

        private const val PNF_BASE = 0x80000000L
        private const val EARLY_PNF_BASE = 0x90000000L
        private const val PREDATOR_BASE = 0xDD000000L

        /** The log format from the log upload identifier; the table is in docs/protocol/shearwater-peregrine.md. */
        internal fun logFormat(data: ByteArray): LogFormat {
            if (data.size != 9) throw ProtocolException("Unexpected log format identifier (${data.size} bytes)")
            return when (val base = u32be(data, 1)) {
                PNF_BASE -> LogFormat(PNF_BASE, PredatorParser.PNF_FORMAT_ID)
                ShearwaterLogbook.PREDATOR_LIKE_BASE, EARLY_PNF_BASE, PREDATOR_BASE ->
                    LogFormat(ShearwaterLogbook.PREDATOR_LIKE_BASE, PredatorParser.PETREL_FORMAT_ID)
                else -> throw ProtocolException("Unknown Shearwater log format ${base.toString(16)}")
            }
        }

        /** The serial as eight upper-case hex digits (sent as ASCII hex, maybe NUL or space padded), else null. */
        internal fun serial(data: ByteArray): String? =
            padded(data).uppercase().takeIf { it.length == 8 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }

        /** The firmware version string, e.g. "V92", without padding. */
        internal fun firmware(data: ByteArray): String? = padded(data).ifEmpty { null }

        /** ASCII without the NULs and spaces a fixed-size identifier is padded with. */
        private fun padded(data: ByteArray): String = data.decodeToString().trim { it <= ' ' }
    }
}
