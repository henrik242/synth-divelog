package no.synth.divelog.desktop

import com.fazecast.jSerialComm.SerialPort
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Link
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Protocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoFamily
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.transport.JSerialCommTransport
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Command-line capture tool for a Suunto over the USB cable, for bring-up and
 * debugging before there is a desktop download UI. Opens the serial port, runs the
 * selected family's protocol through a RecordingTransport, prints the decoded dives
 * and saves the raw transcript (which can become a replay regression fixture).
 *
 * The D9/HelO2 family has no download yet, so for it the tool instead dumps the whole
 * memory over ReadMemory and saves it as a .bin, which is what the directory/profile
 * parser gets reverse-engineered from.
 *
 * Run: ./gradlew :app:desktop:suuntoCapture --args="[VYPER|D9] [portName]"
 * The args can come in any order: an arg matching a family name selects the family,
 * anything else is taken as the port name. With no port it lists the available ports
 * and tries to auto-pick a USB-serial one.
 */
fun main(args: Array<String>) {
    val ports = JSerialCommTransport.availablePortNames()
    println("Available serial ports: ${if (ports.isEmpty()) "(none)" else ports.joinToString()}")

    // An arg that names a family is the family; "probe" selects probe mode; anything
    // else is the port name. This way "D9" or "D9 probe" leaves the port to auto-detect.
    val familyArg = args.firstNotNullOfOrNull { runCatching { SuuntoFamily.valueOf(it.uppercase()) }.getOrNull() }
    val keywords = setOf("probe", "readprobe")
    val portArg = args.firstOrNull {
        !keywords.contains(it.lowercase()) && runCatching { SuuntoFamily.valueOf(it.uppercase()) }.isFailure
    }

    val family = familyArg ?: SuuntoFamily.VYPER
    val portName = portArg
        ?: ports.firstOrNull { it.contains("usbserial", true) || it.contains("tty.usb", true) || it.contains("ttyUSB", true) }
    if (portName == null) {
        println("No port given and no USB-serial port auto-detected.")
        println("Usage: ./gradlew :app:desktop:suuntoCapture --args=\"[VYPER|D9] [portName]\"")
        return
    }

    // "probe" sweeps the DTR/RTS/duplex line settings and reports which one gets a
    // reply, for when the device stays silent and we do not yet know how the cable
    // drives the line. It bypasses the normal transport.
    if (args.any { it.equals("probe", ignoreCase = true) }) {
        probeLines(portName)
        return
    }

    // "readprobe" establishes comms with GetVersion then tries a D9 ReadMemory several
    // ways (read count, settle time, echo read, address) and prints the raw bytes, to
    // find why the device answers GetVersion but not ReadMemory.
    if (args.any { it.equals("readprobe", ignoreCase = true) }) {
        readProbe(portName)
        return
    }

    println("Opening $portName as ${family.displayName}")
    println("Line settings: ${family.serialParams}")

    val recording = RecordingTransport(JSerialCommTransport.byName(portName, family.serialParams)) {
        System.currentTimeMillis()
    }
    try {
        recording.open()
        val protocol = family.protocol(recording)
        if (protocol is SuuntoD9Protocol) {
            dumpD9Memory(protocol)
        } else {
            downloadDives(family, protocol)
        }
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace()
    } finally {
        saveTranscript(recording)
        runCatching { recording.close() }
    }
}

private fun downloadDives(family: SuuntoFamily, protocol: no.synth.divelog.core.divecomputer.DiveComputerProtocol) {
    val listener = object : DownloadListener {
        override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model}")
        override fun onDiveDownloaded(index: Int) = println("  downloaded dive ${index + 1}")
    }
    val raw = protocol.download(null, listener, CancellationSignal.NONE)
    println("Downloaded ${raw.size} raw dive(s)")
    family.parser()?.let { parser ->
        raw.forEach { r ->
            runCatching { parser.parse(r) }
                .onSuccess { d ->
                    println("  dive #${d.number ?: "?"}  max=${d.maxDepthMm ?: 0} mm  ${d.durationSeconds}s  ${d.samples.size} samples")
                }
                .onFailure { println("  parse failed: ${it.message}") }
        }
    }
}

/**
 * The D9 download is not written yet, so instead read the whole memory over
 * ReadMemory and save it. The dive directory and profile layout are reversed from
 * this dump against what the device screen shows. Reads page by page until an error
 * (which marks the end of readable memory) or [DUMP_END].
 */
private fun dumpD9Memory(protocol: SuuntoD9Protocol) {
    val info = runCatching { protocol.readDeviceInfo() }.getOrNull()
    if (info != null) println("Device: ${info.vendor} ${info.model} firmware ${info.firmware}")

    val out = ByteArrayOutputStream()
    var addr = 0
    try {
        while (addr < DUMP_END) {
            val n = minOf(SuuntoD9Link.MAX_PAGE, DUMP_END - addr)
            out.write(protocol.link.readMemory(addr, n))
            addr += n
            if (addr % 0xC00 == 0) println("  read ${addr} / $DUMP_END bytes (0x${addr.toString(16)})")
        }
        println("  read all $DUMP_END bytes")
    } catch (e: Exception) {
        println("  stopped at 0x${addr.toString(16)} (${addr} bytes): ${e.message}")
    }

    val dump = out.toByteArray()
    if (dump.isEmpty()) {
        println("No memory read.")
        return
    }
    val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    val bin = File(dir, "suunto-d9-dump-${System.currentTimeMillis()}.bin")
    runCatching { bin.writeBytes(dump) }
        .onSuccess { println("Memory dump saved: ${bin.absolutePath} (${dump.size} bytes)") }
}

private fun saveTranscript(recording: RecordingTransport) {
    val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    val file = File(dir, "suunto-capture-${System.currentTimeMillis()}.transcript.txt")
    runCatching { file.writeText(recording.transcript().toText()) }
        .onSuccess { println("Transcript saved: ${file.absolutePath}") }
}

/** Upper bound for the D9 memory dump; reads stop earlier if the device errors first. */
private const val DUMP_END = 0x8000

/**
 * Sweep the serial line settings at 9600 8N1 and send a D9 GetVersion to each,
 * printing whatever comes back. Used when the device stays silent: it shows which
 * DTR/RTS/duplex combination the cable actually needs. GetVersion is 0F 00 00 with
 * an XOR checksum (0F), so the packet is 0F 00 00 0F; the reply echoes 0F and is 8
 * bytes.
 */
private fun probeLines(portName: String) {
    val request = byteArrayOf(0x0F, 0x00, 0x00, 0x0F)
    data class Combo(
        val label: String,
        val dtr: Boolean,
        val rts: Boolean,
        val halfDuplex: Boolean,
        val rtsTxHigh: Boolean = true,
    )
    val combos = listOf(
        Combo("half-duplex  D9 polarity (RTS low=TX, high=RX)", dtr = true, rts = false, halfDuplex = true, rtsTxHigh = false),
        Combo("half-duplex  Vyper polarity (RTS high=TX, low=RX)", dtr = true, rts = false, halfDuplex = true, rtsTxHigh = true),
        Combo("full-duplex  DTR=1 RTS=1", dtr = true, rts = true, halfDuplex = false),
        Combo("full-duplex  DTR=1 RTS=0", dtr = true, rts = false, halfDuplex = false),
        Combo("full-duplex  DTR=0 RTS=0", dtr = false, rts = false, halfDuplex = false),
    )
    println("Probing $portName with GetVersion (${hex(request)}); reply should start with 0f and be 8 bytes.")
    for (c in combos) {
        val port = SerialPort.getCommPort(portName)
        port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1500, 0)
        if (!port.openPort()) {
            println("  ${c.label}: could not open port")
            continue
        }
        try {
            if (c.dtr) port.setDTR() else port.clearDTR()
            if (c.halfDuplex) {
                if (c.rtsTxHigh) port.clearRTS() else port.setRTS() // start in receive
            } else if (c.rts) {
                port.setRTS()
            } else {
                port.clearRTS()
            }
            Thread.sleep(100)
            runCatching { port.flushIOBuffers() }

            if (c.halfDuplex) {
                if (c.rtsTxHigh) port.setRTS() else port.clearRTS() // transmit
                port.writeBytes(request, request.size)
                Thread.sleep(50)
                if (c.rtsTxHigh) port.clearRTS() else port.setRTS() // receive
            } else {
                port.writeBytes(request, request.size)
            }

            val buf = ByteArray(16)
            val n = port.readBytes(buf, buf.size)
            val got = if (n > 0) buf.copyOf(n) else ByteArray(0)
            val verdict = when {
                got.isEmpty() -> "nothing"
                got.size >= 4 && got[0] == 0x0F.toByte() && !got.contentEquals(request.copyOf(got.size)) -> "REPLY -> this is the config"
                got.contentEquals(request.copyOf(got.size)) -> "echo of our own bytes (single-wire cable)"
                else -> "unexpected"
            }
            println("  ${c.label}: ${if (got.isEmpty()) "(no bytes)" else hex(got)}  [$verdict]")
        } finally {
            runCatching { port.closePort() }
        }
        Thread.sleep(300)
    }
    println("If every combo says nothing: the cable likely is not this Suunto's interface,")
    println("the device is asleep, or the contacts are not seated. If one says REPLY, tell me which.")
}

/**
 * Establish comms with GetVersion, then try a D9 ReadMemory several ways and print the
 * raw reply for each. The device answers GetVersion but ignores our ReadMemory, so this
 * varies the read count, the settle time, whether an echo is read back after the write
 * (as the reference driver does), and the address, to find the combination it accepts.
 */
private fun readProbe(portName: String) {
    val port = SerialPort.getCommPort(portName)
    port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 0)
    if (!port.openPort()) {
        println("Could not open $portName")
        return
    }
    try {
        port.setDTR()
        port.clearRTS() // RTS high = transmit, low = receive; start in receive
        Thread.sleep(100)
        runCatching { port.flushIOBuffers() }

        // Half-duplex exchange: RTS high to transmit, drain, optionally read the echo
        // back, then RTS low to receive and read the reply.
        fun exchange(command: ByteArray, maxReply: Int, txSettleMs: Long, readEcho: Boolean): ByteArray {
            runCatching { port.flushIOBuffers() }
            port.setRTS() // transmit
            port.writeBytes(command, command.size)
            Thread.sleep(txSettleMs)
            if (readEcho) {
                val echo = ByteArray(command.size)
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 600, 0)
                val e = port.readBytes(echo, echo.size)
                println("      echo read: ${if (e > 0) hex(echo.copyOf(e)) else "(none)"}")
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 0)
            }
            port.clearRTS() // receive
            val buf = ByteArray(maxReply)
            val n = port.readBytes(buf, buf.size)
            return if (n > 0) buf.copyOf(n) else ByteArray(0)
        }

        fun readMemoryCmd(address: Int, count: Int): ByteArray {
            val c = byteArrayOf(0x05, 0x00, 0x03, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), (count and 0xFF).toByte())
            var crc = 0
            for (b in c) crc = crc xor (b.toInt() and 0xFF)
            return c + crc.toByte()
        }

        val version = exchange(byteArrayOf(0x0F, 0x00, 0x00, 0x0F), 16, 50, readEcho = false)
        println("GetVersion: ${if (version.isEmpty()) "(no bytes)" else hex(version)}")
        if (version.isEmpty()) {
            println("No version reply, aborting read probe.")
            return
        }
        // The version reply can arrive in chunks; drain the tail so it does not get
        // mistaken for the next reply (flushIOBuffers is unreliable on this cable).
        run {
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 300, 0)
            val drain = ByteArray(32)
            val d = port.readBytes(drain, drain.size)
            if (d > 0) println("  (drained version tail: ${hex(drain.copyOf(d))})")
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 0)
        }

        // The reference download reads the serial and a header at 0x0190 before the
        // dive ring buffer; low addresses may be unmapped. Try the real addresses.
        data class Case(val label: String, val addr: Int, val count: Int, val settle: Long, val echo: Boolean)
        val cases = listOf(
            Case("addr 0x0190 count 8   settle 50", 0x0190, 8, 50, false),
            Case("addr 0x0190 count 4   settle 50", 0x0190, 4, 50, false),
            Case("addr 0x0190 count 8   settle 50  +echo", 0x0190, 8, 50, true),
            Case("addr 0x0023 count 4   settle 50", 0x0023, 4, 50, false),
            Case("addr 0x0000 count 4   settle 50", 0x0000, 4, 50, false),
            Case("addr 0x0000 count 120 settle 50", 0x0000, 120, 50, false),
        )
        for (c in cases) {
            val cmd = readMemoryCmd(c.addr, c.count)
            println("ReadMemory ${c.label}  (sent ${hex(cmd)}):")
            val reply = exchange(cmd, c.count + 16, c.settle, c.echo)
            println("      reply: ${if (reply.isEmpty()) "(no bytes)" else hex(reply)}")
            // Drain any trailing bytes so a chunked reply does not bleed into the next case.
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 300, 0)
            val tail = ByteArray(160)
            val t = port.readBytes(tail, tail.size)
            if (t > 0) println("      (drained tail: ${hex(tail.copyOf(t))})")
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 0)
            Thread.sleep(200)
        }
    } finally {
        runCatching { port.closePort() }
    }
}

private fun hex(data: ByteArray): String = data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
