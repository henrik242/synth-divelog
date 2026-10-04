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
    fun d9FamilyUsesHalfDuplex9600NoneNoEcho() {
        val p = SuuntoFamily.D9.serialParams
        assertEquals(9600, p.baudRate)
        assertEquals(Parity.NONE, p.parity)
        assertTrue(p.halfDuplex)
        // Same RTS-high-to-transmit polarity as Vyper, but no echo on the proper cable.
        assertTrue(p.rtsTransmitHigh)
        assertTrue(!p.discardsEcho)
    }

    @Test
    fun protocolAndParserMatchTheFamily() {
        val transport = FakeSuuntoVyperDevice(SyntheticVyper.squareDiveImage().memory)
        assertTrue(SuuntoFamily.VYPER.protocol(transport) is SuuntoVyperProtocol)
        assertNotNull(SuuntoFamily.VYPER.parser())
        assertTrue(SuuntoFamily.D9.protocol(transport) is SuuntoD9Protocol)
        assertNotNull(SuuntoFamily.D9.parser())
    }
}
