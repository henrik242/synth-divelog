package no.synth.divelog.core.divecomputer

import no.synth.divelog.core.divecomputer.shearwater.PredatorDump
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.suunto.FakeSuuntoVyperDevice
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Dump
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Parser
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Protocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperDump
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperParser
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperProtocol
import no.synth.divelog.core.divecomputer.suunto.SyntheticVyper
import no.synth.divelog.core.divecomputer.transport.Parity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The registry hands out the right line settings, protocol and parser. */
class DiveComputerKindTest {
    @Test
    fun vyperFamilyUsesHalfDuplex2400Odd() {
        val p = DiveComputerKind.SUUNTO_VYPER.serialParams
        assertEquals(2400, p.baudRate)
        assertEquals(Parity.ODD, p.parity)
        assertTrue(p.halfDuplex)
        assertTrue(p.dtr)
    }

    @Test
    fun vyper2FamilyUsesHalfDuplex9600NoneNoEcho() {
        val p = DiveComputerKind.SUUNTO_VYPER2.serialParams
        assertEquals(9600, p.baudRate)
        assertEquals(Parity.NONE, p.parity)
        assertTrue(p.halfDuplex)
        // Same RTS-high-to-transmit polarity as Vyper, but no echo on the proper cable.
        assertTrue(p.rtsTransmitHigh)
        assertFalse(p.discardsEcho)
    }

    @Test
    fun shearwaterIsAPlainFullDuplexStream() {
        val p = DiveComputerKind.SHEARWATER_PETREL.serialParams
        assertFalse(p.halfDuplex)
        assertEquals(p, DiveComputerKind.SHEARWATER_PREDATOR.serialParams)
    }

    @Test
    fun wireTimeCountsStartParityAndStopBits() {
        // 7 bytes x 10 bits at 9600 baud = 7.3 ms; 9 bytes x 11 bits (8O1) at 2400 = 41.3 ms.
        assertEquals(8, DiveComputerKind.SUUNTO_VYPER2.serialParams.wireTimeMs(7))
        assertEquals(42, DiveComputerKind.SUUNTO_VYPER.serialParams.wireTimeMs(9))
    }

    @Test
    fun protocolMatchesTheKind() {
        val transport = FakeSuuntoVyperDevice(SyntheticVyper.squareDiveImage().memory)
        assertTrue(DiveComputerKind.SUUNTO_VYPER.protocol(transport) is SuuntoVyperProtocol)
        assertTrue(DiveComputerKind.SUUNTO_VYPER2.protocol(transport) is SuuntoVyper2Protocol)
    }

    @Test
    fun parserForEveryStoredFormat() {
        assertTrue(DiveComputerKind.parserFor(SuuntoVyperDump.FORMAT_ID) is SuuntoVyperParser)
        assertTrue(DiveComputerKind.parserFor(SuuntoVyper2Dump.FORMAT_ID) is SuuntoVyper2Parser)
        assertTrue(DiveComputerKind.parserFor(PredatorDump.FORMAT_ID) is PredatorParser)
        assertTrue(DiveComputerKind.parserFor(PredatorParser.PETREL_FORMAT_ID) is PredatorParser)
        assertTrue(DiveComputerKind.parserFor(PredatorParser.PNF_FORMAT_ID) is PredatorParser)
        assertNull(DiveComputerKind.parserFor("subsurface-xml"))
        // Every kind's own formats resolve.
        for (kind in DiveComputerKind.entries) {
            for (id in kind.formatIds) assertTrue(DiveComputerKind.parserFor(id) != null, id)
        }
    }

    @Test
    fun newestUntilStopsAtTheKnownDiveAndCaps() {
        val ids = sequenceOf("e", "d", "c", "b", "a")
        assertEquals(listOf("e", "d"), ids.newestUntil("c", null) { it }.toList())
        assertEquals(listOf("e"), ids.newestUntil("c", 1) { it }.toList())
        assertEquals(listOf("e", "d", "c", "b", "a"), ids.newestUntil(null, 0) { it }.toList())
    }

    @Test
    fun fnv1aMatchesTheReferenceVectors() {
        assertEquals("811c9dc5", fnv1a(ByteArray(0)))
        assertEquals("e40c292c", fnv1a("a".encodeToByteArray()))
    }
}
