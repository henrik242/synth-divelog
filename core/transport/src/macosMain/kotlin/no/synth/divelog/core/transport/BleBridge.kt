// The ble-bridge helper: how the desktop app, a JVM, reaches CoreBluetooth. It runs the same
// CoreBluetoothScanner and CoreBluetoothTransport as iOS and relays them over stdin/stdout
// (MacBleTransport and MacBleScanner are the JVM ends).
//
//   ble-bridge scan            scan 20 s; stdout gets "DEVICE <id>\t<name>" per Peregrine as it
//                              shows up, and "PROBLEM <text>" (empty text: none) as Bluetooth changes
//   ble-bridge connect <id>    open the link; stderr says "CONNECTED <max write size>", or
//                              "ERROR <message>" before exiting non-zero. Then frames, each a BE16
//                              length and that many bytes: one from stdin is one write, and each
//                              notification goes to stdout as one. Exits when stdin closes or the
//                              link drops (an ERROR line first).
package no.synth.divelog.core.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.exit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

fun main(args: Array<String>) {
    when {
        args.firstOrNull() == "scan" -> scan()
        args.firstOrNull() == "connect" && args.size > 1 -> connect(args[1])
        else -> {
            status("usage: ble-bridge scan | ble-bridge connect <id>")
            exit(2)
        }
    }
}

private fun scan() {
    val scanner = CoreBluetoothScanner()
    scanner.scan()
    val printed = HashMap<String, String?>()
    var problem: String? = null
    val end = TimeSource.Monotonic.markNow() + SCAN_SECONDS.seconds
    while (end.hasNotPassedNow()) {
        // The scanner's callbacks run on the main queue, which this run loop serves.
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.25))
        for (d in scanner.found()) {
            if (d.id in printed && printed[d.id] == d.name) continue
            printed[d.id] = d.name
            writeAll(STDOUT, "DEVICE ${d.id}\t${d.name.orEmpty()}\n".encodeToByteArray())
        }
        val now = scanner.problem()
        if (now != problem) {
            problem = now
            writeAll(STDOUT, "PROBLEM ${now.orEmpty()}\n".encodeToByteArray())
        }
    }
}

private fun connect(id: String) {
    val link = CoreBluetoothTransport(id)
    try {
        link.open()
    } catch (e: TransportException) {
        fail(e)
    }
    status("CONNECTED ${link.maxWriteSize ?: DEFAULT_WRITE_SIZE}")
    // device -> stdout on a background queue; stdin -> device here, then the orderly close.
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
        val buffer = ByteArray(MAX_FRAME)
        while (true) {
            val n = try {
                link.read(buffer, 0, buffer.size, 60_000)
            } catch (_: TransportTimeoutException) {
                continue
            } catch (_: TransportClosedException) {
                return@dispatch_async // closed below
            } catch (e: TransportException) {
                fail(e)
            }
            writeAll(STDOUT, byteArrayOf((n shr 8).toByte(), n.toByte()) + buffer.copyOf(n))
        }
    }
    while (true) {
        val frame = readFrame() ?: break
        try {
            link.write(frame)
        } catch (e: TransportException) {
            fail(e)
        }
    }
    link.close() // waits for the device to drop the link
    exit(0)
}

/** One frame from stdin, or null at its end. */
private fun readFrame(): ByteArray? {
    val header = readFully(2) ?: return null
    val length = (header[0].toInt() and 0xFF shl 8) or (header[1].toInt() and 0xFF)
    return readFully(length)
}

@OptIn(ExperimentalForeignApi::class)
private fun readFully(n: Int): ByteArray? {
    val out = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = out.usePinned { platform.posix.read(STDIN, it.addressOf(got), (n - got).toULong()) }
        if (r <= 0) return null
        got += r.toInt()
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAll(fd: Int, data: ByteArray) {
    var done = 0
    while (done < data.size) {
        val w = data.usePinned { platform.posix.write(fd, it.addressOf(done), (data.size - done).toULong()) }
        if (w <= 0) exit(1) // the app went away
        done += w.toInt()
    }
}

private fun status(line: String) = writeAll(STDERR, "$line\n".encodeToByteArray())

private fun fail(e: TransportException): Nothing {
    status("ERROR ${e.message}")
    exit(1)
    throw e // not reached
}

private const val STDIN = 0
private const val STDOUT = 1
private const val STDERR = 2
private const val SCAN_SECONDS = 20
private const val MAX_FRAME = 512

/** The default 23-byte ATT MTU carries 20. */
private const val DEFAULT_WRITE_SIZE = 20
