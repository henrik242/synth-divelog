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
    val keywords = setOf("probe", "readprobe", "difftest")
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

    // "difftest" reads 0x0190 three ways in one session (raw probe-style, through
    // JSerialCommTransport+link, and +RecordingTransport) to isolate why the raw read
    // works but the real transport path does not.
    if (args.any { it.equals("difftest", ignoreCase = true) }) {
        diffTest(portName)
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
 * this dump against what the device screen shows. Low addresses (below the serial at
 * 0x0023) are unmapped and never answer, so a failed page is filled with 0xFF and the
 * dump keeps going rather than aborting; the saved .bin stays address-aligned.
 */
private fun dumpD9Memory(protocol: SuuntoD9Protocol) {
    val info = runCatching { protocol.readDeviceInfo() }.getOrNull()
    if (info != null) println("Device: ${info.vendor} ${info.model} firmware ${info.firmware}")

    // Canary: a known-mapped address (the header at 0x0190) must answer. If it does not,
    // the read path is broken (not just unmapped low addresses), so abort fast rather than
    // grinding through the whole address space retrying every page.
    val canary = runCatching { protocol.link.readMemory(0x0190, 8) }
    canary.onSuccess { println("Canary 0x0190: ${hex(it)}") }
    if (canary.isFailure) {
        println("Canary 0x0190 failed: ${canary.exceptionOrNull()?.message}")
        println("Read path not working; aborting before the full dump.")
        return
    }

    val out = ByteArrayOutputStream()
    var addr = 0
    var failed = 0
    while (addr < DUMP_END) {
        val n = minOf(SuuntoD9Link.MAX_PAGE, DUMP_END - addr)
        val page = runCatching { protocol.link.readMemory(addr, n) }.getOrNull()
        if (page != null) {
            out.write(page)
        } else {
            out.write(ByteArray(n) { 0xFF.toByte() }) // placeholder, keep the .bin address-aligned
            failed++
            if (failed <= 24) println("  page 0x${addr.toString(16)} did not answer, filled with ff")
        }
        addr += n
        if (addr % 0xC00 == 0) println("  ${addr} / $DUMP_END bytes (0x${addr.toString(16)})")
    }
    println("Done: $addr bytes, $failed page(s) unreadable")

    val dump = out.toByteArray()
    if (dump.all { it == 0xFF.toByte() }) {
        println("Every page was unreadable; not saving.")
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
    val mode = SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING
    port.setComPortTimeouts(mode, 3000, 2000)
    if (!port.openPort()) {
        println("Could not open $portName")
        return
    }
    try {
        port.setDTR()
        port.clearRTS() // RTS high = transmit, low = receive; start in receive
        Thread.sleep(100)
        runCatching { port.flushIOBuffers() }

        fun setReadTimeout(ms: Int) = port.setComPortTimeouts(mode, ms, 2000)

        // Half-duplex exchange: RTS high to transmit, blocking write, a short settle to
        // let the command drain off the wire, then RTS low to receive. The device replies
        // fast, so if the settle is too long the reply is lost while we are still in
        // transmit; too short and we clip our own command. The sweep below finds the window.
        fun exchange(command: ByteArray, maxReply: Int, txSettleMs: Long): ByteArray {
            runCatching { port.flushIOBuffers() }
            port.setRTS() // transmit
            port.writeBytes(command, command.size)
            if (txSettleMs > 0) Thread.sleep(txSettleMs)
            port.clearRTS() // receive
            val buf = ByteArray(maxReply)
            val n = port.readBytes(buf, buf.size)
            return if (n > 0) buf.copyOf(n) else ByteArray(0)
        }

        fun drain(label: String) {
            setReadTimeout(300)
            val t = ByteArray(200)
            val n = port.readBytes(t, t.size)
            if (n > 0) println("      (drained $label: ${hex(t.copyOf(n))})")
            setReadTimeout(3000)
        }

        fun readMemoryCmd(address: Int, count: Int): ByteArray {
            val c = byteArrayOf(0x05, 0x00, 0x03, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), (count and 0xFF).toByte())
            var crc = 0
            for (b in c) crc = crc xor (b.toInt() and 0xFF)
            return c + crc.toByte()
        }

        val version = exchange(byteArrayOf(0x0F, 0x00, 0x00, 0x0F), 16, 50)
        println("GetVersion: ${if (version.isEmpty()) "(no bytes)" else hex(version)}")
        if (version.isEmpty()) {
            println("No version reply, aborting read probe.")
            return
        }
        drain("version tail")

        // Sweep short settle times. The command is 7 bytes (~7 ms on the wire); a clean
        // reply for count 8 at 0x0190 is 05 00 0b 01 90 08 <8 data> <crc> (15 bytes, starts
        // with 05). Whichever settle yields that cleanly is the turnaround we need.
        println("Sweeping settle times for ReadMemory 0x0190 count 8 (want 05 00 0b 01 90 08 ...):")
        for (settle in listOf(0L, 2L, 4L, 6L, 8L, 10L, 12L, 16L, 20L, 30L)) {
            val reply = exchange(readMemoryCmd(0x0190, 8), 24, settle)
            println("  settle ${settle}ms: ${if (reply.isEmpty()) "(no bytes)" else hex(reply)}")
            drain("tail")
            Thread.sleep(150)
        }
    } finally {
        runCatching { port.closePort() }
    }
}

/**
 * Read 0x0190 three ways in one session to isolate why the raw probe reads it but the
 * real transport path does not: raw inline (A), through JSerialCommTransport + link (B),
 * and with RecordingTransport in front (C).
 */
private fun diffTest(portName: String) {
    val cmd0190 = run {
        val c = byteArrayOf(0x05, 0x00, 0x03, 0x01, 0x90.toByte(), 0x08)
        var crc = 0
        for (b in c) crc = crc xor (b.toInt() and 0xFF)
        c + crc.toByte()
    }
    val getVersion = byteArrayOf(0x0F, 0x00, 0x00, 0x0F)

    println("Phase A: raw inline read of 0x0190 (probe-style, settle 6/10):")
    run {
        val port = SerialPort.getCommPort(portName)
        port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, 300, 2000)
        if (!port.openPort()) { println("  could not open"); return@run }
        try {
            port.setDTR(); port.clearRTS(); Thread.sleep(100); runCatching { port.flushIOBuffers() }
            fun rawExchange(cmd: ByteArray, settle: Long): ByteArray {
                runCatching { port.flushIOBuffers() }
                port.setRTS(); port.writeBytes(cmd, cmd.size); Thread.sleep(settle); port.clearRTS()
                val buf = ByteArray(24); val n = port.readBytes(buf, buf.size)
                return if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
            rawExchange(getVersion, 10)
            Thread.sleep(120); runCatching { port.flushIOBuffers() }
            var ok = 0
            repeat(10) { i ->
                val r = rawExchange(cmd0190, if (i % 2 == 0) 6 else 10)
                if (r.isNotEmpty() && r[0] == 0x05.toByte()) ok++
                val d = ByteArray(64); port.readBytes(d, d.size)
                Thread.sleep(120)
            }
            println("  raw: $ok/10 got a 0x05 reply")
        } finally { runCatching { port.closePort() } }
    }
    Thread.sleep(300)

    println("Phase B: JSerialCommTransport + SuuntoD9Link.readMemory(0x0190, 8):")
    run {
        val t = JSerialCommTransport.byName(portName, SuuntoFamily.D9.serialParams)
        try {
            t.open()
            val link = SuuntoD9Link(t)
            runCatching { link.readVersion() }.onFailure { println("  version failed: ${it.message}") }
            var ok = 0
            repeat(3) {
                runCatching { link.readMemory(0x0190, 8) }
                    .onSuccess { ok++; println("  ok: ${hex(it)}") }
                    .onFailure { println("  fail: ${it.message}") }
            }
            println("  transport: $ok/3")
        } finally { runCatching { t.close() } }
    }
    Thread.sleep(300)

    println("Phase C: RecordingTransport + link:")
    run {
        val t = RecordingTransport(JSerialCommTransport.byName(portName, SuuntoFamily.D9.serialParams))
        try {
            t.open()
            val link = SuuntoD9Link(t)
            runCatching { link.readVersion() }
            var ok = 0
            repeat(3) { if (runCatching { link.readMemory(0x0190, 8) }.isSuccess) ok++ }
            println("  recording transport: $ok/3")
        } finally { runCatching { t.close() } }
    }
}

private fun hex(data: ByteArray): String = data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
