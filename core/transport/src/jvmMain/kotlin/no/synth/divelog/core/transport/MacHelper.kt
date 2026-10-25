package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The Bluetooth helpers bundled with the macOS desktop app: `rfcomm-bridge` (Bluetooth
 * Classic, Swift) and `ble-bridge` (Bluetooth LE, core:transport's macOS executable).
 */
internal object MacHelper {
    /**
     * The helper [name]: the `synth.<property>` system property (set by `gradlew run`), else
     * the app's bundled resources. Null where there is none (not macOS, or not built).
     */
    fun locate(name: String, property: String): File? = listOfNotNull(
        System.getProperty("synth.$property")?.let(::File),
        System.getProperty("compose.application.resources.dir")?.let { File(it, name) },
    ).firstOrNull { it.canExecute() }
}

/**
 * A running helper that links to a device: it reports on stderr "CONNECTED <detail>" once
 * the link is up, or "ERROR <message>" before exiting, and relays the link on stdin/stdout.
 */
internal class HelperLink(helper: File, vararg args: String) {
    val process: Process = ProcessBuilder(helper.absolutePath, *args).start()
    private val status = LinkedBlockingQueue<String>()
    @Volatile private var error: String? = null

    private val statusReader = Thread({
        // Ends with the helper; killing it (stop) closes the stream under the reader.
        runCatching {
            process.errorStream.bufferedReader().forEachLine {
                if (it.startsWith(BridgeProtocol.ERROR)) error = it.removePrefix(BridgeProtocol.ERROR)
                status.put(it)
            }
        }
    }, "${helper.name}-status").apply {
        isDaemon = true
        start()
    }

    /** The helper's last ERROR message, once it has gone (waiting briefly for its stderr), or null. */
    fun exitError(): String? {
        statusReader.join(EXIT_WAIT_MS)
        return error
    }

    /**
     * Wait for the link to come up and return the CONNECTED detail; [abort] ends the wait.
     * Other stderr lines (a runtime warning) are skipped.
     */
    fun awaitConnected(device: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var line: String?
        do {
            line = try {
                status.poll((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        } while (line != null && line != ABORTED && !line.startsWith(BridgeProtocol.CONNECTED) && !line.startsWith(BridgeProtocol.ERROR))
        if (line != null && line.startsWith(BridgeProtocol.CONNECTED)) return line.removePrefix(BridgeProtocol.CONNECTED).trim()
        process.destroy()
        if (line == ABORTED) throw TransportClosedException()
        val reason = line?.removePrefix(BridgeProtocol.ERROR) ?: "no answer within ${timeoutMs / 1000} s"
        throw TransportException("Could not connect to $device: $reason")
    }

    fun abort() {
        status.offer(ABORTED)
    }

    /**
     * Close stdin, which makes the helper drop the link and exit, then wait for it briefly;
     * killing it sooner could leave the device connected.
     */
    fun stop() {
        abort()
        runCatching { process.outputStream.close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
    }

    private companion object {
        const val ABORTED = "ABORTED"
        const val EXIT_WAIT_MS = 2_000L
    }
}
