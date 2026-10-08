package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The line discipline, step by step against a fake line with a fake clock. */
class SerialLineTransportTest {
    /** Records each primitive with the fake time it happened at; reads serve [incoming]. */
    private class FakeLine(var writeTakesMs: Long = 0) : SerialLine {
        var now = 1_000L
        val log = mutableListOf<String>()
        val incoming = ArrayDeque<ByteArray>()

        override fun open() { log += "open" }
        override fun close() { log += "close" }
        override fun setRts(on: Boolean) { log += "rts $on @$now" }
        override fun setDtr(on: Boolean) { log += "dtr $on" }
        override fun write(data: ByteArray) {
            log += "write ${data.size} @$now"
            now += writeTakesMs
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
            val next = incoming.removeFirstOrNull()
            if (next == null) {
                now += minOf(timeoutMs, 100) // a short port read that times out
                return 0
            }
            next.copyInto(buffer, offset)
            log += "read ${next.size} @$now"
            return next.size
        }
        override fun flush() { log += "flush @$now" }
        override fun nowMs() = now
        override fun sleep(ms: Long) {
            log += "sleep $ms"
            now += ms
        }
    }

    // HelO2 settings: 9600 8N1, half-duplex, no echo, 600 ms quiet gap, 100 ms power-up.
    private val helo2 = SerialParams(baudRate = 9600, halfDuplex = true, discardsEcho = false, txIdleMs = 600, powerUpMs = 100)

    @Test
    fun opensWithDtrReceiveDirectionPowerUpAndFlush() {
        val line = FakeLine()
        SerialLineTransport(line, helo2).open()
        assertEquals(listOf("open", "dtr true", "rts false @1000", "sleep 100", "flush @1100"), line.log)
    }

    @Test
    fun halfDuplexWriteKeepsTheGapFlushesAndTurnsAroundAfterTheWireTime() {
        val line = FakeLine()
        val transport = SerialLineTransport(line, helo2)
        transport.open()
        line.incoming += ByteArray(5)
        transport.read(ByteArray(5), 0, 5, 500) // last activity at 1100
        line.log.clear()
        line.now += 200

        transport.write(ByteArray(7))

        // 400 ms more of quiet makes 600; then flush, transmit, 8 ms wire + 2 ms margin, receive.
        assertEquals(
            listOf("sleep 400", "flush @1700", "rts true @1700", "write 7 @1700", "sleep 10", "rts false @1710"),
            line.log,
        )
    }

    @Test
    fun aSlowWriteNeedsNoDrainWait() {
        val line = FakeLine(writeTakesMs = 20) // the write returned after the bytes were out
        val transport = SerialLineTransport(line, helo2.copy(txIdleMs = 0))
        transport.open()
        line.log.clear()
        transport.write(ByteArray(7))
        assertEquals(listOf("flush @1100", "rts true @1100", "write 7 @1100", "rts false @1120"), line.log)
    }

    @Test
    fun echoIsDroppedAfterTheSettleTimes() {
        val line = FakeLine()
        val vyper = SerialParams(baudRate = 2400, halfDuplex = true, txSettleMs = 200, rxSettleMs = 400)
        val transport = SerialLineTransport(line, vyper)
        transport.open()
        line.log.clear()
        line.incoming += ByteArray(3) // the echo
        line.incoming += byteArrayOf(42) // the reply

        transport.write(ByteArray(3))
        val buffer = ByteArray(1)
        transport.read(buffer, 0, 1, 1_000)

        assertEquals(42, buffer[0].toInt())
        assertEquals(listOf("flush", "rts", "write", "sleep", "sleep", "rts", "sleep", "read", "read"), line.log.map { it.substringBefore(' ') })
    }

    @Test
    fun fullDuplexWritesStraightThrough() {
        val line = FakeLine()
        val transport = SerialLineTransport(line, SerialParams(baudRate = 115200))
        transport.open()
        line.log.clear()
        transport.write(ByteArray(4))
        assertEquals(listOf("write 4 @1000"), line.log)
    }

    @Test
    fun readWaitsUntilTheDeadline() {
        val line = FakeLine()
        val transport = SerialLineTransport(line, helo2)
        transport.open()
        val start = line.now
        assertFailsWith<TransportTimeoutException> { transport.read(ByteArray(1), 0, 1, 450) }
        assertEquals(450, line.now - start)
    }

    @Test
    fun closedTransportRefusesIo() {
        val line = FakeLine()
        val transport = SerialLineTransport(line, helo2)
        transport.open()
        transport.close()
        assertFailsWith<TransportClosedException> { transport.read(ByteArray(1), 0, 1, 100) }
        assertFailsWith<TransportClosedException> { transport.write(ByteArray(1)) }
    }
}
