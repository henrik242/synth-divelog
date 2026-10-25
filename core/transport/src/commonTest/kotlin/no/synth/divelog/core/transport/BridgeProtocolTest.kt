package no.synth.divelog.core.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BridgeProtocolTest {
    @Test
    fun aFrameGivesBackItsLengthAndBytes() {
        for (n in listOf(0, 1, 255, 256, 511, 0xFFFF)) {
            val data = ByteArray(n) { (it * 7 + 0x80).toByte() }
            val frame = BridgeProtocol.frame(data)
            assertEquals(n, BridgeProtocol.frameLength(frame[0], frame[1]), "length $n")
            assertContentEquals(data, frame.copyOfRange(2, frame.size))
        }
    }

    @Test
    fun aFrameOfPartOfABufferTakesOnlyThatPart() {
        assertContentEquals(byteArrayOf(0, 2, 9, 8), BridgeProtocol.frame(byteArrayOf(9, 8, 7), length = 2))
    }
}
