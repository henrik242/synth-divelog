package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JVM end of the ble-bridge helper, against shell scripts that speak its protocol. */
class MacBleTransportTest {
    private fun helper(script: String): File =
        Files.createTempFile("ble-bridge", ".sh").toFile().apply {
            writeText("#!/bin/sh\n$script\n")
            setExecutable(true)
            deleteOnExit()
        }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun framesKeepTheirBoundariesAndTheHelpersErrorEndsTheLink() {
        // A warning before CONNECTED is skipped; after the frames the helper reports why it stopped.
        val bridge = helper(
            """
            echo 'objc[1]: a runtime warning' >&2
            echo 'CONNECTED 182' >&2
            printf '\000\003\001\002\003\000\001\011'
            echo 'ERROR lost it' >&2
            exit 1
            """.trimIndent(),
        )
        val link = MacBleTransport(bridge, "ID")
        link.open()
        assertEquals(182, link.maxWriteSize)
        val buf = ByteArray(10)
        assertEquals(3, link.read(buf, 0, 10, 2_000))
        assertEquals(1, link.read(buf, 3, 10, 2_000))
        assertEquals(listOf<Byte>(1, 2, 3, 9), buf.take(4))
        assertEquals("lost it", assertFailsWith<TransportException> { link.read(buf, 0, 10, 2_000) }.message)
        link.close()
    }

    @Test
    fun aWriteIsOneFrame() {
        val out = Files.createTempFile("ble-bridge", ".out").toFile().apply { deleteOnExit() }
        val link = MacBleTransport(helper("echo 'CONNECTED 20' >&2\ncat > '${out.absolutePath}'"), "ID")
        link.open()
        link.write(byteArrayOf(5, 6))
        link.write(ByteArray(300))
        link.close() // closes stdin, so cat finishes the file
        val bytes = out.readBytes()
        assertEquals(listOf<Byte>(0, 2, 5, 6, 1, 44), bytes.take(6))
        assertEquals(2 + 2 + 2 + 300, bytes.size)
    }

    @Test
    fun aConnectErrorIsReported() {
        val link = MacBleTransport(helper("echo 'ERROR Turn on Bluetooth' >&2\nexit 1"), "ID")
        val e = assertFailsWith<TransportException> { link.open() }
        assertEquals("Could not connect to the dive computer: Turn on Bluetooth", e.message)
    }

    @Test
    fun closeWhileConnectingEndsTheWait() {
        val link = MacBleTransport(helper("sleep 30"), "ID")
        var failure: Throwable? = null
        val opener = thread { failure = runCatching { link.open() }.exceptionOrNull() }
        Thread.sleep(200)
        link.close()
        opener.join(2_000)
        assertTrue(failure is TransportClosedException, "open ended with $failure")
    }

    @Test
    fun aTransportClosedBeforeOpenNeverStartsTheHelper() {
        val started = Files.createTempFile("ble-bridge", ".started").toFile().apply { delete(); deleteOnExit() }
        val link = MacBleTransport(helper("touch '${started.absolutePath}'\necho 'CONNECTED 20' >&2\ncat"), "ID")
        link.close()
        assertFailsWith<TransportClosedException> { link.open() }
        assertFalse(started.exists())
    }

    @Test
    fun aLinkWithoutAWriteSizeIsRefused() {
        val link = MacBleTransport(helper("echo 'CONNECTED ?' >&2\ncat"), "ID")
        assertEquals("The Bluetooth helper reported no write size", assertFailsWith<TransportException> { link.open() }.message)
    }

    @Test
    fun aScanListsDevicesAndClearsAProblem() {
        // The helper reports a problem, then waits for the test to clear it.
        val cleared = Files.createTempFile("ble-bridge", ".cleared").toFile().apply { delete(); deleteOnExit() }
        val scanner = MacBleScanner(
            helper(
                """
                printf 'PROBLEM Turn on Bluetooth\n'
                while [ ! -e '${cleared.absolutePath}' ]; do sleep 0.05; done
                printf 'PROBLEM \nDEVICE AAAA\tPeregrine TX\nDEVICE BBBB\t\n'
                """.trimIndent(),
            ),
        )
        scanner.scan()
        waitFor("the problem") { scanner.problem() != null }
        assertEquals("Turn on Bluetooth", scanner.problem())
        cleared.createNewFile()
        waitFor("both devices") { scanner.found().size == 2 }
        assertNull(scanner.problem())
        assertEquals(listOf(ScannedDevice("BBBB", null), ScannedDevice("AAAA", "Peregrine TX")), scanner.found())
        scanner.stop()
    }
}
