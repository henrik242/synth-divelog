package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.transport.FrameChannel
import no.synth.divelog.core.divecomputer.transport.Slip
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShearwaterBlePacketsTest {
    @Test
    fun aShortFrameGoesOutAsOnePacketWithItsLead() {
        val link = PacketLink()
        ShearwaterBlePackets(link).write(byteArrayOf(1, 2, 3, 0xC0.toByte()))
        assertEquals(1, link.written.size)
        assertContentEquals(byteArrayOf(1, 0, 1, 2, 3, 0xC0.toByte()), link.written[0])
    }

    @Test
    fun aLongFrameIsSplitIntoNumberedPackets() {
        val link = PacketLink()
        val frame = ByteArray(65) { it.toByte() }
        ShearwaterBlePackets(link).write(frame)
        assertEquals(listOf(32, 32, 7), link.written.map { it.size })
        assertEquals(listOf(0, 1, 2), link.written.map { it[1].toInt() })
        link.written.forEach { assertEquals(3, it[0].toInt()) }
        assertContentEquals(frame, link.written.fold(ByteArray(0)) { acc, p -> acc + p.copyOfRange(2, p.size) })
    }

    @Test
    fun writesAreCutToWhatTheLinkCarries() {
        // A default 23-byte MTU carries 20 bytes per write: 18 of frame after the lead.
        val link = PacketLink(maxWriteSize = 20)
        val frame = ByteArray(22) { it.toByte() }
        ShearwaterBlePackets(link).write(frame)
        assertEquals(listOf(20, 6), link.written.map { it.size })
        assertEquals(listOf(2, 2), link.written.map { it[0].toInt() })
        // A link carrying more still gets libdivecomputer's 32-byte packets.
        val roomy = PacketLink(maxWriteSize = 244)
        ShearwaterBlePackets(roomy).write(frame)
        assertEquals(listOf(24), roomy.written.map { it.size })
    }

    @Test
    fun readsDropEachLeadAndJoinAFrameAcrossPackets() {
        // An escaped END split across two notifications, plus a lead-only packet between them.
        val payload = byteArrayOf(0x01, 0xC0.toByte(), 0x02)
        val slip = Slip.encode(payload) // 01 DB DC 02 C0
        val link = PacketLink(
            byteArrayOf(2, 0) + slip.copyOfRange(0, 2),
            byteArrayOf(2, 1),
            byteArrayOf(2, 1) + slip.copyOfRange(2, slip.size),
        )
        assertContentEquals(payload, FrameChannel(ShearwaterBlePackets(link)).readFrame())
    }

    @Test
    fun thirtyBytesFitOnePacketAndThirtyOneTakeTwo() {
        for ((size, packets) in listOf(30 to 1, 31 to 2)) {
            val link = PacketLink()
            ShearwaterBlePackets(link).write(ByteArray(size))
            assertEquals(packets, link.written.size, "$size bytes")
        }
    }

    @Test
    fun framesTheLinkCannotCarryAreRejected() {
        // A write must fit more than the lead, and a frame at most 255 packets.
        assertFailsWith<ProtocolException> { ShearwaterBlePackets(PacketLink(maxWriteSize = 2)).write(ByteArray(1)) }
        assertFailsWith<ProtocolException> { ShearwaterBlePackets(PacketLink()).write(ByteArray(30 * 256)) }
    }

    @Test
    fun aPacketLargerThanTheReadIsRejected() {
        assertFailsWith<ProtocolException> {
            ShearwaterBlePackets(PacketLink(byteArrayOf(1, 0, 1, 2, 3))).read(ByteArray(2), 0, 2, 100)
        }
    }

    @Test
    fun aPacketWithoutItsLeadIsRejected() {
        assertFailsWith<ProtocolException> {
            ShearwaterBlePackets(PacketLink(byteArrayOf(1))).read(ByteArray(64), 0, 64, 100)
        }
    }
}

/** A packet link that records each written packet and serves [packets] one per read. */
private class PacketLink(vararg packets: ByteArray, override val maxWriteSize: Int? = null) : Transport {
    val written = mutableListOf<ByteArray>()
    private val incoming = ArrayDeque(packets.toList())

    override fun open() {}

    override fun close() {}

    override fun write(data: ByteArray) {
        written += data
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val packet = incoming.removeFirstOrNull() ?: throw TransportTimeoutException()
        packet.copyInto(buffer, offset)
        return packet.size
    }
}
