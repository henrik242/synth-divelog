package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Shearwater's packet layer over Bluetooth LE, between the SLIP stream [ShearwaterLink]
 * speaks and a GATT [packetLink]. Each SLIP frame (already escaped and END-terminated)
 * goes out in packets of at most [PACKET_SIZE] bytes, or the link's [Transport.maxWriteSize]
 * when that is smaller (a default 23-byte MTU carries 20), each led by two bytes: the packet
 * count for the frame and this packet's index. Every notification the device sends back
 * carries the same two-byte lead, which is dropped; the SLIP bytes after it run on
 * across packets, escapes included.
 *
 * [packetLink] must keep packet boundaries: one [Transport.write] sends one packet and one
 * [Transport.read] returns the whole of one packet. Follows libdivecomputer's
 * `shearwater_common_slip_write` / `_slip_read` for `DC_TRANSPORT_BLE`.
 */
class ShearwaterBlePackets(private val packetLink: Transport) : Transport {
    private val packet = ByteArray(MAX_NOTIFICATION)

    override fun open() = packetLink.open()

    override fun close() = packetLink.close()

    override fun write(data: ByteArray) {
        val packetSize = minOf(PACKET_SIZE, packetLink.maxWriteSize ?: PACKET_SIZE)
        if (packetSize <= HEADER_SIZE) throw ProtocolException("BLE link carries only $packetSize bytes per write")
        val payloadSize = packetSize - HEADER_SIZE
        val count = (data.size + payloadSize - 1) / payloadSize
        if (count > 0xFF) throw ProtocolException("Frame too long for BLE packets: ${data.size} bytes")
        for (index in 0 until count) {
            val from = index * payloadSize
            val to = minOf(from + payloadSize, data.size)
            packetLink.write(byteArrayOf(count.toByte(), index.toByte()) + data.copyOfRange(from, to))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        while (true) {
            val n = packetLink.read(packet, 0, packet.size, timeoutMs)
            if (n < HEADER_SIZE) throw ProtocolException("BLE packet too short ($n bytes)")
            // A packet with only the lead carries no SLIP bytes; wait for the next one.
            if (n == HEADER_SIZE) continue
            val payload = n - HEADER_SIZE
            if (payload > length) throw ProtocolException("BLE packet of $n bytes overflows a $length-byte read")
            packet.copyInto(buffer, offset, HEADER_SIZE, n)
            return payload
        }
    }

    companion object {
        /**
         * Largest packet written, lead included, as libdivecomputer cuts them. Every request
         * we send fits one such packet, escapes included.
         */
        internal const val PACKET_SIZE = 32
        private const val HEADER_SIZE = 2

        /** Largest notification: the 517-byte MTU Android 14+ always asks for, less the ATT header. */
        private const val MAX_NOTIFICATION = 514
    }
}
