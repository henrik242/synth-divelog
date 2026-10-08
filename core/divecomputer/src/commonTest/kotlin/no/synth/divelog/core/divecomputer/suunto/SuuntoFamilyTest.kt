package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.transport.Parity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The family selector hands out the right line settings, protocol and parser. */
class SuuntoFamilyTest {
    @Test
    fun vyperFamilyUsesHalfDuplex2400Odd() {
        val p = SuuntoFamily.VYPER.serialParams
        assertEquals(2400, p.baudRate)
        assertEquals(Parity.ODD, p.parity)
        assertTrue(p.halfDuplex)
        assertTrue(p.dtr)
    }

    @Test
    fun vyper2FamilyUsesHalfDuplex9600NoneNoEcho() {
        val p = SuuntoFamily.VYPER2.serialParams
        assertEquals(9600, p.baudRate)
        assertEquals(Parity.NONE, p.parity)
        assertTrue(p.halfDuplex)
        // Same RTS-high-to-transmit polarity as Vyper, but no echo on the proper cable.
        assertTrue(p.rtsTransmitHigh)
        assertTrue(!p.discardsEcho)
    }

    @Test
    fun wireTimeCountsStartParityAndStopBits() {
        // 7 bytes x 10 bits at 9600 baud = 7.3 ms; 9 bytes x 11 bits (8O1) at 2400 = 41.3 ms.
        assertEquals(8, SuuntoFamily.VYPER2.serialParams.wireTimeMs(7))
        assertEquals(42, SuuntoFamily.VYPER.serialParams.wireTimeMs(9))
    }

    @Test
    fun protocolAndParserMatchTheFamily() {
        val transport = FakeSuuntoVyperDevice(SyntheticVyper.squareDiveImage().memory)
        assertTrue(SuuntoFamily.VYPER.protocol(transport) is SuuntoVyperProtocol)
        assertNotNull(SuuntoFamily.VYPER.parser())
        assertTrue(SuuntoFamily.VYPER2.protocol(transport) is SuuntoVyper2Protocol)
        assertNotNull(SuuntoFamily.VYPER2.parser())
    }
}
