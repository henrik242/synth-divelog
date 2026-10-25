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
 * Petrel 2, the Petrel 3 and the Peregrine. It is the Petrel 1's manifest download
 * ([ShearwaterPetrel1Protocol]) with four additions: the serial, firmware and model number
 * are read by identifier, the log format identifier says where the dives live and which
 * layout they use, and the session ends with a shutdown command so the device leaves its
 * Bluetooth screen cleanly.
 *
 * The model number names the computer (a Peregrine TX as such) and must be one of [models],
 * the ones this download was picked for; another stops it with a message saying which
 * computer it is, so its dives are not stored under the wrong model.
 *
 * With [packets] the [transport] is a Bluetooth LE GATT link, which [ShearwaterBlePackets]
 * turns into the SLIP stream the command layer speaks; without, it is that stream already
 * (Bluetooth Classic). [model] is the computer that was picked.
 */
class ShearwaterPetrelFamilyProtocol private constructor(
    transport: Transport,
    private val model: String,
    private val models: Set<Int>,
    packets: Boolean,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    private val link = ShearwaterLink(if (packets) ShearwaterBlePackets(transport) else transport, timeoutMs)
    private val logbook = ShearwaterLogbook(ShearwaterMemory(link))
    private var info: DeviceInfo? = null

    override fun readDeviceInfo(): DeviceInfo = info ?: run {
        // In libdivecomputer's order: serial, firmware, model.
        val serial = serial(link.readIdentifier(ID_SERIAL))
        val firmware = firmware(link.readIdentifier(ID_FIRMWARE))
        val number = modelNumber(link.readIdentifier(ID_MODEL))
        if (number !in models) throw ProtocolException(mismatch(number, model))
        DeviceInfo(vendor = SHEARWATER, model = MODEL_NAMES[number] ?: model, serial = serial, firmware = firmware)
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
         * has its own protocol ([ShearwaterPetrel1Protocol]).
         */
        private val MODEL_NAMES = mapOf(
            3 to "Petrel 2", 4 to "NERD", 5 to "Perdix", 6 to "Perdix AI", 7 to "NERD 2", 8 to "Teric",
            9 to "Peregrine", 10 to "Petrel 3", 11 to "Perdix 2", 12 to "Tern", 13 to "Peregrine TX", 14 to "Perdix 3",
        )
        private val PEREGRINES = setOf(9, 13)
        private val PETREL_2 = setOf(3)
        private val PETREL_3 = setOf(10)

        /** The Peregrine (and Peregrine TX): Bluetooth LE only. */
        fun peregrine(transport: Transport) = ShearwaterPetrelFamilyProtocol(transport, "Peregrine", PEREGRINES, packets = true)

        /** The Petrel 3: Bluetooth LE only. */
        fun petrel3(transport: Transport) = ShearwaterPetrelFamilyProtocol(transport, "Petrel 3", PETREL_3, packets = true)

        /**
         * The Petrel 2, over Bluetooth Classic or LE. A packet link is the one that knows its
         * write size ([Transport.maxWriteSize] is null for a byte stream), so [transport] must
         * be open; a replayed LE capture carries its size in the transcript.
         */
        fun petrel2(transport: Transport) =
            ShearwaterPetrelFamilyProtocol(transport, "Petrel 2", PETREL_2, packets = transport.maxWriteSize != null)

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

        /** What to tell the user when the computer is model [number], not the [picked] one. */
        internal fun mismatch(number: Int, picked: String): String {
            val name = MODEL_NAMES[number] ?: return "This Shearwater (model $number) is not supported yet."
            val readable = number in PEREGRINES + PETREL_2 + PETREL_3
            return if (readable) {
                "This computer is a $name, not a $picked. Choose $name and download again."
            } else {
                "This computer is a $name, which is not supported yet."
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
