package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleLinkTest {
    @Test
    fun aReadReturnsBytesOfOneNotificationOnly() {
        val q = NotificationQueue()
        q.offer(byteArrayOf(1, 2, 3))
        q.offer(byteArrayOf(4))
        val buf = ByteArray(10)
        assertEquals(2, q.read(buf, 0, 2, 100))
        assertEquals(1, q.read(buf, 2, 8, 100)) // the rest of the first, not the second
        assertEquals(1, q.read(buf, 3, 7, 100))
        assertEquals(listOf<Byte>(1, 2, 3, 4), buf.take(4))
        assertFailsWith<TransportTimeoutException> { q.read(buf, 0, 10, 10) }
    }

    @Test
    fun theLinkEndingIsReportedAfterWhatCameBefore() {
        val q = NotificationQueue()
        q.offer(byteArrayOf(7))
        q.end(TransportException("lost"))
        val buf = ByteArray(4)
        assertEquals(1, q.read(buf, 0, 4, 100))
        assertEquals("lost", assertFailsWith<TransportException> { q.read(buf, 0, 4, 100) }.message)
        assertEquals("lost", assertFailsWith<TransportException> { q.read(buf, 0, 4, 100) }.message)
        q.close()
        assertFailsWith<TransportClosedException> { q.read(buf, 0, 4, 100) }
    }

    @Test
    fun aPeregrineIsKnownByNameOrByTheServiceWithoutOne() {
        assertTrue(ShearwaterBle.isPeregrine("Peregrine TX", advertisesService = false))
        assertTrue(ShearwaterBle.isPeregrine(null, advertisesService = true))
        assertFalse(ShearwaterBle.isPeregrine("Perdix 2", advertisesService = true))
        assertFalse(ShearwaterBle.isPeregrine(null, advertisesService = false))
    }

    @Test
    fun theSerialLinkIsSearchedInShearwatersServiceFirstAndNeverInSigOnes() {
        val services = listOf(
            "180A", // device information, as CoreBluetooth prints it
            "0000180f-0000-1000-8000-00805f9b34fb", // battery, as Android prints it
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            ShearwaterBle.SERVICE_UUID.uppercase(),
        )
        assertEquals(listOf(ShearwaterBle.SERVICE_UUID.uppercase(), "6e400001-b5a3-f393-e0a9-e50e24dcca9e"), ShearwaterBle.serialServices(services) { it })
    }
}
