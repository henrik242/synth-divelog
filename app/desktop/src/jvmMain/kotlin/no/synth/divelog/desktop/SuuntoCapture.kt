package no.synth.divelog.desktop

import com.fazecast.jSerialComm.SerialPort
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Dump
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Link
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Parser
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Protocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoFamily
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.transport.JSerialCommTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

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
    val keywords = setOf("probe", "readprobe", "difftest", "jnatest", "rawdump", "suite", "proto", "echotest")
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

    // "jnatest" opens the port directly via JNA and tests the tcdrain turnaround, which
    // jSerialComm cannot do. If this reads 0x0190 reliably, the transport moves to JNA.
    if (args.any { it.equals("jnatest", ignoreCase = true) }) {
        posixJnaTest(portName)
        return
    }

    // "rawdump" brute-forces a full D9 memory dump using the raw read path with heavy
    // randomized retries, for when the turnaround only lands a fraction of the time. Best
    // run right after replugging so the HelO2 is freshly in Data transfer.
    if (args.any { it.equals("rawdump", ignoreCase = true) }) {
        rawDump(portName)
        return
    }

    // "suite" characterizes a cable end to end (baud, line config, echo, echo-read vs
    // fixed-settle turnaround in both polarities), for the third-party echoing cable.
    if (args.any { it.equals("suite", ignoreCase = true) }) {
        cableTestSuite(portName)
        return
    }

    // "proto" runs the SHARED SuuntoD9Protocol.download over a bare transport (no
    // RecordingTransport wrapper) - exactly the code path Android would use - to check
    // whether the shared Transport read is reliable enough to replace the desktop raw path.
    if (args.any { it.equals("proto", ignoreCase = true) }) {
        protoDownload(portName, family)
        return
    }

    // "echotest" measures the echo-read turnaround: after each write it reads the sent bytes
    // back (which only works if the cable reflects them under jSerialComm) and uses that as
    // the turnaround sync, then compares first-try success against the raw+retries baseline.
    // This decides whether SerialParams.echoSync is worth enabling on this hardware.
    if (args.any { it.equals("echotest", ignoreCase = true) }) {
        echoTest(portName)
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
 * Thread.sleep is coarse, which could smear the turnaround across the narrow ~6-10ms
 * window and explain the low, run-varying hit rate. This sweeps a microsecond-precise
 * busy-wait settle at fixed values (20 attempts each) to see whether precise timing lands
 * reliably. It reads the version first so we know the device is live this run.
 */
private fun diffTest(portName: String) {
    val cmd0190 = run {
        val c = byteArrayOf(0x05, 0x00, 0x03, 0x01, 0x90.toByte(), 0x08)
        var crc = 0
        for (b in c) crc = crc xor (b.toInt() and 0xFF)
        c + crc.toByte()
    }
    val getVersion = byteArrayOf(0x0F, 0x00, 0x00, 0x0F)
    fun busyWaitMs(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) { /* spin for precise timing */ }
    }

    val port = SerialPort.getCommPort(portName)
    port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, 300, 2000)
    if (!port.openPort()) { println("Could not open $portName"); return }
    try {
        port.setDTR(); port.clearRTS(); Thread.sleep(100); runCatching { port.flushIOBuffers() }

        fun exchange(cmd: ByteArray, settleMs: Long): ByteArray {
            runCatching { port.flushIOBuffers() }
            port.setRTS(); port.writeBytes(cmd, cmd.size); busyWaitMs(settleMs); port.clearRTS()
            val buf = ByteArray(32)
            val n = port.readBytes(buf, buf.size)
            return if (n > 0) buf.copyOf(n) else ByteArray(0)
        }

        val v = exchange(getVersion, 8)
        println("Version check: ${if (v.isEmpty()) "(no reply - device may be asleep!)" else hex(v)}")
        Thread.sleep(150)

        println("Precise busy-wait settle sweep on 0x0190 (hit = reply starting 0x05):")
        for (s in listOf(3L, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14)) {
            var ok = 0
            repeat(20) {
                val r = exchange(cmd0190, s)
                if (r.isNotEmpty() && r[0] == 0x05.toByte()) ok++
                Thread.sleep(50)
            }
            println("  settle ${s}ms busy -> $ok/20")
        }
    } finally {
        runCatching { port.closePort() }
    }
}

/** Raised when a raw D9 capture cannot complete; the message is user-facing. */
class SuuntoCaptureException(message: String) : Exception(message)

/**
 * Capture the D9/HelO2 memory over the raw half-duplex read path and return it as an
 * address-aligned 0x8000 image. The turnaround lands only a fraction of the time, so
 * each page is retried with randomized settle and back-off; this is the only read path
 * that works reliably on this hardware.
 *
 * The region below 0x019A is mostly unmapped, and a large page that spans unmapped
 * memory can fail outright - including the one covering the directory header at 0x0190,
 * which would then read back 0xFF and make the decode walk find zero dives. So the
 * serial (0x0023) and the header (0x0190) are read with small dedicated reads at those
 * exact addresses (which answer reliably even when a big page over them does not), and
 * only the profile ring (0x019A..0x7FFE) is dumped in 120-byte pages. This is both
 * correct and much faster, since the dead low pages are skipped. A warm-up canary at
 * 0x0190 and a consecutive-failure circuit breaker keep it from grinding when the
 * device is asleep or degraded; unreadable pages stay 0xFF so the image is aligned.
 *
 * [onProgress] is called with (bytesDone, total) over the ring as pages complete, and
 * [isCancelled] is polled between pages so a UI can stop a long read. Throws
 * [SuuntoCaptureException] when the port cannot open, the canary never answers, the
 * read is cancelled, or the ring comes back empty. Blocking; call off the UI thread.
 */
fun captureD9Image(
    portName: String,
    isCancelled: () -> Boolean = { false },
    onProgress: (bytesDone: Int, total: Int) -> Unit = { _, _ -> },
): ByteArray {
    val rnd = java.util.Random()
    val port = SerialPort.getCommPort(portName)
    port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, 150, 2000)
    if (!port.openPort()) throw SuuntoCaptureException("Could not open serial port $portName")
    try {
        port.setDTR(); port.clearRTS(); Thread.sleep(100); runCatching { port.flushIOBuffers() }

        fun crc(b: ByteArray, n: Int): Byte {
            var c = 0
            for (i in 0 until n) c = c xor (b[i].toInt() and 0xFF)
            return c.toByte()
        }
        fun readMemoryCmd(address: Int, count: Int): ByteArray {
            val c = byteArrayOf(0x05, 0x00, 0x03, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), (count and 0xFF).toByte())
            return c + crc(c, c.size)
        }

        // One raw turnaround attempt; returns the validated data bytes or null.
        fun attempt(address: Int, count: Int): ByteArray? {
            val cmd = readMemoryCmd(address, count)
            val replyLen = 7 + count
            runCatching { port.flushIOBuffers() }
            port.setRTS()
            port.writeBytes(cmd, cmd.size)
            Thread.sleep(4L + rnd.nextInt(13)) // settle 4..16ms
            port.clearRTS()
            val buf = ByteArray(replyLen)
            var got = 0
            val deadline = System.currentTimeMillis() + 400
            while (got < replyLen && System.currentTimeMillis() < deadline) {
                val tmp = ByteArray(replyLen - got)
                val n = port.readBytes(tmp, tmp.size)
                if (n > 0) { tmp.copyInto(buf, got, 0, n); got += n }
            }
            if (got != replyLen) return null
            if (buf[0] != 0x05.toByte()) return null
            if (buf[replyLen - 1] != crc(buf, replyLen - 1)) return null
            return buf.copyOfRange(6, 6 + count)
        }

        fun readPage(address: Int, count: Int, maxTries: Int): ByteArray? {
            repeat(maxTries) {
                attempt(address, count)?.let { return it }
                Thread.sleep(rnd.nextInt(26).toLong()) // 0..25ms, break phase-lock
            }
            return null
        }

        val image = ByteArray(DUMP_END) { 0xFF.toByte() }

        // Warm-up canary and directory header in one small read at the exact address.
        // A full page spanning the unmapped memory below it would not answer.
        val header = readPage(SuuntoD9Dump.HEADER_OFFSET, SuuntoD9Dump.HEADER_SIZE, 80)
            ?: throw SuuntoCaptureException(
                "The dive computer is not responding. Unplug and replug it so it is freshly in Data transfer, then try again.",
            )
        header.copyInto(image, SuuntoD9Dump.HEADER_OFFSET)

        // Serial for device identity; small dedicated read, best effort.
        readPage(SuuntoD9Dump.SERIAL_OFFSET, SuuntoD9Dump.SERIAL_SIZE, 40)
            ?.copyInto(image, SuuntoD9Dump.SERIAL_OFFSET)

        // Profile ring only, in retryable pages.
        val ringTotal = SuuntoD9Dump.RB_PROFILE_END - SuuntoD9Dump.RB_PROFILE_BEGIN
        var addr = SuuntoD9Dump.RB_PROFILE_BEGIN
        var done = 0
        var consecutiveFail = 0
        while (addr < SuuntoD9Dump.RB_PROFILE_END) {
            if (isCancelled()) throw SuuntoCaptureException("Download cancelled")
            val n = minOf(SuuntoD9Link.MAX_PAGE, SuuntoD9Dump.RB_PROFILE_END - addr)
            val page = readPage(addr, n, 60)
            if (page != null) {
                page.copyInto(image, addr)
                consecutiveFail = 0
            } else {
                consecutiveFail++ // page stays 0xFF
            }
            addr += n
            done += n
            onProgress(done, ringTotal)
            if (consecutiveFail >= 25) break // device degraded; keep what we have
        }

        val ringReadable = (SuuntoD9Dump.RB_PROFILE_BEGIN until SuuntoD9Dump.RB_PROFILE_END)
            .any { image[it] != 0xFF.toByte() }
        if (!ringReadable) {
            throw SuuntoCaptureException("Nothing usable was read from the dive computer.")
        }
        return image
    } finally {
        runCatching { port.closePort() }
    }
}

/** Walk a captured 0x8000 image into the raw dives its profile ring buffer holds. */
fun extractD9Dives(dump: ByteArray): List<RawDive> {
    require(dump.size >= SuuntoD9Dump.RB_PROFILE_END) { "image too small to decode" }
    val header = dump.copyOfRange(SuuntoD9Dump.HEADER_OFFSET, SuuntoD9Dump.HEADER_OFFSET + SuuntoD9Dump.HEADER_SIZE)
    val ring = dump.copyOfRange(SuuntoD9Dump.RB_PROFILE_BEGIN, SuuntoD9Dump.RB_PROFILE_END)
    return SuuntoD9Dump.extract(ring, header)
}

/**
 * CLI rawdump mode: capture the whole memory using the proven raw read path, save the
 * .bin for fixtures, and print the decoded dives. Shares [captureD9Image] with the
 * desktop download so there is one capture implementation.
 */
private fun rawDump(portName: String) {
    val start = System.currentTimeMillis()
    val dump = try {
        captureD9Image(portName) { done, total ->
            if (done % 0x600 == 0 || done == total) {
                println("  0x${done.toString(16)} (${done * 100 / total}%), ${(System.currentTimeMillis() - start) / 1000}s")
            }
        }
    } catch (e: SuuntoCaptureException) {
        println(e.message)
        return
    }
    val readable = dump.count { it != 0xFF.toByte() }
    println("Captured ${dump.size} bytes ($readable non-ff).")
    val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    val bin = File(dir, "suunto-d9-dump-${System.currentTimeMillis()}.bin")
    runCatching { bin.writeBytes(dump) }
        .onSuccess { println("Memory dump saved: ${bin.absolutePath}") }
    decodeDump(dump)
}

/** Decode a captured 0x8000 memory image into dives using the real parser. */
private fun decodeDump(dump: ByteArray) {
    val raw = runCatching { extractD9Dives(dump) }.getOrElse {
        println("Could not walk the ring: ${it.message}"); return
    }
    println("Decoded ${raw.size} dive(s):")
    val parser = SuuntoD9Parser()
    raw.forEachIndexed { i, r ->
        runCatching { parser.parse(r) }
            .onSuccess { d ->
                println("  #${i + 1}  ${Instant.ofEpochSecond(d.startEpochSeconds)}  ${d.durationSeconds / 60}min  max=${(d.maxDepthMm ?: 0) / 1000.0}m  ${d.samples.size} samples")
            }
            .onFailure { println("  #${i + 1}  parse failed: ${it.message}") }
    }
}

/**
 * Run the shared [no.synth.divelog.core.divecomputer.DiveComputerProtocol.download] over a
 * bare transport (no recording wrapper) and decode, to test whether the shared Transport
 * read path is reliable enough to be the single cross-platform download.
 */
private fun protoDownload(portName: String, family: SuuntoFamily) {
    val start = System.currentTimeMillis()
    val transport = JSerialCommTransport.byName(portName, family.serialParams)
    try {
        transport.open()
        val protocol = family.protocol(transport)
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model} serial ${info.serial} fw ${info.firmware}")
            override fun onProgress(current: Int, total: Int) {
                if (current % 0x600 == 0) println("  ${current} / $total (${current * 100 / total}%), ${(System.currentTimeMillis() - start) / 1000}s")
            }
            override fun onDiveDownloaded(index: Int) {}
        }
        val raw = protocol.download(null, listener, CancellationSignal.NONE)
        println("Downloaded ${raw.size} raw dive(s) in ${(System.currentTimeMillis() - start) / 1000}s")
        val parser = family.parser()
        if (parser != null) {
            raw.forEachIndexed { i, r ->
                runCatching { parser.parse(r) }
                    .onSuccess { d -> println("  #${i + 1}  ${Instant.ofEpochSecond(d.startEpochSeconds)}  ${d.durationSeconds / 60}min  max=${(d.maxDepthMm ?: 0) / 1000.0}m  ${d.samples.size} samples") }
                    .onFailure { println("  #${i + 1}  parse failed: ${it.message}") }
            }
        }
    } catch (e: Exception) {
        println("proto download failed: ${e.message}")
    } finally {
        runCatching { transport.close() }
    }
}

/**
 * Measure the echo-read turnaround against the raw+retries baseline, both over jSerialComm.
 *
 * The reference driver syncs the half-duplex turnaround by reading the command bytes back off
 * the wire after each write (the read blocks until the bytes are physically out) instead of
 * waiting a fixed settle, so every reply read lands first try. An earlier probe suggested the
 * sent bytes may NOT be reflected under jSerialComm's config, so this quantifies it: it runs
 * GetVersion then ~30 ReadMemory(0x0190) exchanges reading the echo back each time, counting
 * how often the echo matched the command and how often the reply validated, with timing, then
 * runs the same count through the existing raw+retries path for comparison. If echoMatched is
 * high and echo-read validates first try as fast as or faster than raw+retries, enabling
 * SerialParams.echoSync is worth it; if echoMatched stays near zero, keep raw+retries.
 */
private fun echoTest(portName: String) {
    val rounds = 30
    val addr = 0x0190
    val count = 8
    fun crc(b: ByteArray, n: Int): Byte {
        var c = 0
        for (i in 0 until n) c = c xor (b[i].toInt() and 0xFF)
        return c.toByte()
    }
    fun readMemoryCmd(address: Int, n: Int): ByteArray {
        val c = byteArrayOf(0x05, 0x00, 0x03, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), (n and 0xFF).toByte())
        return c + crc(c, c.size)
    }
    val cmd = readMemoryCmd(addr, count)
    val replyLen = 7 + count // 05 00 03 addrHi addrLo count <count data> crc

    val port = SerialPort.getCommPort(portName)
    port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, 150, 2000)
    if (!port.openPort()) { println("Could not open $portName"); return }
    try {
        port.setDTR(); port.clearRTS(); Thread.sleep(100); runCatching { port.flushIOBuffers() }

        fun readBlocking(n: Int, timeoutMs: Long): ByteArray {
            val buf = ByteArray(n)
            var got = 0
            val deadline = System.currentTimeMillis() + timeoutMs
            while (got < n && System.currentTimeMillis() < deadline) {
                val tmp = ByteArray(n - got)
                val r = port.readBytes(tmp, tmp.size)
                if (r > 0) { tmp.copyInto(buf, got, 0, r); got += r }
            }
            return buf.copyOf(got)
        }

        // Echo-read turnaround: transmit, read the sent bytes back as the sync, receive, read reply.
        fun echoExchange(command: ByteArray, expectReply: Int): Triple<Boolean, Boolean, ByteArray> {
            runCatching { port.flushIOBuffers() }
            port.setRTS() // transmit
            port.writeBytes(command, command.size)
            val echo = readBlocking(command.size, 500)
            port.clearRTS() // receive
            val echoMatched = echo.contentEquals(command)
            val reply = readBlocking(expectReply, 500)
            val replyValid = reply.size == expectReply && reply[0] == command[0] && reply[expectReply - 1] == crc(reply, expectReply - 1)
            return Triple(echoMatched, replyValid, reply)
        }

        // Raw+retries baseline: fixed+jittered settle, no echo read, resend until a reply validates.
        val rnd = java.util.Random()
        fun rawExchange(command: ByteArray, expectReply: Int, maxTries: Int): Pair<Boolean, Int> {
            repeat(maxTries) { attempt ->
                runCatching { port.flushIOBuffers() }
                port.setRTS()
                port.writeBytes(command, command.size)
                Thread.sleep(4L + rnd.nextInt(13)) // settle 4..16ms, as the working path does
                port.clearRTS()
                val reply = readBlocking(expectReply, 500)
                if (reply.size == expectReply && reply[0] == command[0] && reply[expectReply - 1] == crc(reply, expectReply - 1)) {
                    return true to (attempt + 1)
                }
                Thread.sleep(rnd.nextInt(26).toLong())
            }
            return false to maxTries
        }

        val version = echoExchange(byteArrayOf(0x0F, 0x00, 0x00, 0x0F), 8)
        println("GetVersion (echo-read): echoMatched=${version.first} replyValid=${version.second} ${if (version.third.isEmpty()) "(no bytes)" else hex(version.third)}")
        if (!version.second) {
            println("Device did not answer GetVersion; is it awake and seated? Aborting echotest.")
            return
        }
        Thread.sleep(150)

        println("Echo-read turnaround on 0x$addr (x$rounds):")
        var echoMatched = 0
        var echoReplyValid = 0
        val echoStart = System.currentTimeMillis()
        repeat(rounds) {
            val (m, v, _) = echoExchange(cmd, replyLen)
            if (m) echoMatched++
            if (v) echoReplyValid++
            Thread.sleep(50)
        }
        val echoMs = System.currentTimeMillis() - echoStart
        println("  echoMatched=$echoMatched/$rounds  replyValid(first try)=$echoReplyValid/$rounds  ${echoMs}ms (${echoMs / rounds}ms/read)")

        Thread.sleep(200)

        println("Raw+retries baseline on 0x$addr (x$rounds, up to 60 tries each):")
        var rawValid = 0
        var rawTries = 0
        val rawStart = System.currentTimeMillis()
        repeat(rounds) {
            val (ok, tries) = rawExchange(cmd, replyLen, 60)
            if (ok) rawValid++
            rawTries += tries
            Thread.sleep(50)
        }
        val rawMs = System.currentTimeMillis() - rawStart
        println("  replyValid=$rawValid/$rounds  avgTries=${"%.1f".format(rawTries.toDouble() / rounds)}  ${rawMs}ms (${rawMs / rounds}ms/read)")

        println()
        println("Read: if echoMatched is high and echo-read replyValid(first try) ~= raw replyValid")
        println("while being faster per read, enable SerialParams.echoSync. If echoMatched ~= 0,")
        println("the echo is not reflected under jSerialComm; keep the raw+retries path.")
    } finally {
        runCatching { port.closePort() }
    }
}

private fun hex(data: ByteArray): String = data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
