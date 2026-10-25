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
 * Downloads from every Shearwater since the Petrel 2 as libdivecomputer's `shearwater_petrel.c`
 * reads them: the Petrel 2 and 3, Perdix, NERD, Teric, Peregrine and Tern. It is the Petrel
 * 1's manifest download ([ShearwaterPetrel1Protocol]) with four additions: the serial,
 * firmware and model number are read by identifier, the log format identifier says where the
 * dives live and which layout they use, and the session ends with a shutdown command so the
 * device leaves its Bluetooth screen cleanly.
 *
 * The model number names the computer (a Peregrine TX as such); one libdivecomputer does not
 * know stops the download.
 *
 * A Bluetooth LE GATT link, the one that knows its write size ([Transport.maxWriteSize] is
 * null for a byte stream), goes through [ShearwaterBlePackets], which turns it into the SLIP
 * stream the command layer speaks; Bluetooth Classic is that stream already. So [transport]
 * must be open; a replayed LE capture carries its size in the transcript.
 */
class ShearwaterPetrelFamilyProtocol(transport: Transport, timeoutMs: Long = 3_000) : DiveComputerProtocol {
    private val link = ShearwaterLink(
        if (transport.maxWriteSize != null) ShearwaterBlePackets(transport) else transport,
        timeoutMs,
    )
    private val logbook = ShearwaterLogbook(ShearwaterMemory(link))
    private var info: DeviceInfo? = null

    override fun readDeviceInfo(): DeviceInfo = info ?: run {
        // In libdivecomputer's order: serial, firmware, model.
        val serial = serial(link.readIdentifier(ID_SERIAL))
        val firmware = firmware(link.readIdentifier(ID_FIRMWARE))
        val number = modelNumber(link.readIdentifier(ID_MODEL))
        val model = MODEL_NAMES[number] ?: throw ProtocolException("This Shearwater (model $number) is not supported yet.")
        DeviceInfo(vendor = SHEARWATER, model = model, serial = serial, firmware = firmware)
    }.also { info = it }

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
        private const val ID_MODEL = 0x8060

        /**
         * libdivecomputer's model numbers (`shearwater_common.h`). The Petrel 1 is 3 too, but
         * has its own protocol ([ShearwaterPetrel1Protocol]); the Tern TX is a Tern.
         */
        private val MODEL_NAMES = mapOf(
            3 to "Petrel 2", 4 to "NERD", 5 to "Perdix", 6 to "Perdix AI", 7 to "NERD 2", 8 to "Teric",
            9 to "Peregrine", 10 to "Petrel 3", 11 to "Perdix 2", 12 to "Tern", 13 to "Peregrine TX", 14 to "Perdix 3",
        )

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

        /** The one-byte model number the model identifier carries. */
        internal fun modelNumber(data: ByteArray): Int =
            data.firstOrNull()?.let { it.toInt() and 0xFF } ?: throw ProtocolException("Empty model identifier")

        /** The serial as eight upper-case hex digits (sent as ASCII hex, maybe NUL or space padded), else null. */
        internal fun serial(data: ByteArray): String? =
            padded(data).uppercase().takeIf { it.length == 8 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }

        /** The firmware version string, e.g. "V92", without padding. */
        internal fun firmware(data: ByteArray): String? = padded(data).ifEmpty { null }

        /** ASCII without the NULs and spaces a fixed-size identifier is padded with. */
        private fun padded(data: ByteArray): String = data.decodeToString().trim { it <= ' ' }
    }
}
