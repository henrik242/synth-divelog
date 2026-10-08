package no.synth.divelog.desktop

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.suunto.SuuntoFamily
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Link
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Protocol
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.transport.JSerialCommTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * Command-line capture tool for a Suunto over the USB cable. Opens the serial port and
 * runs the selected family's protocol.
 *
 * - Default: run through a RecordingTransport and save the raw transcript (which can
 *   become a replay regression fixture). The old Vyper family downloads and prints its
 *   dives; the Vyper2 family dumps the whole memory to a .bin instead.
 * - "proto": run the shared download exactly as the apps do, with timing, and print
 *   the parsed dives.
 *
 * Run: ./gradlew :app:desktop:suuntoCapture --args="[VYPER|VYPER2] [proto] [portName]"
 * The args can come in any order: an arg matching a family name selects the family,
 * "proto" selects the mode, anything else is taken as the port name. With no port it
 * tries to auto-pick a USB-serial one.
 */
fun main(args: Array<String>) {
    val ports = JSerialCommTransport.availablePortNames()
    println("Available serial ports: ${if (ports.isEmpty()) "(none)" else ports.joinToString()}")

    val familyArg = args.firstNotNullOfOrNull { runCatching { SuuntoFamily.valueOf(it.uppercase()) }.getOrNull() }
    val proto = args.any { it.equals("proto", ignoreCase = true) }
    val portArg = args.firstOrNull {
        !it.equals("proto", ignoreCase = true) && runCatching { SuuntoFamily.valueOf(it.uppercase()) }.isFailure
    }

    val family = familyArg ?: SuuntoFamily.VYPER
    val portName = portArg
        ?: ports.firstOrNull { it.contains("usbserial", true) || it.contains("tty.usb", true) || it.contains("ttyUSB", true) }
    if (portName == null) {
        println("No port given and no USB-serial port auto-detected.")
        println("Usage: ./gradlew :app:desktop:suuntoCapture --args=\"[VYPER|VYPER2] [proto] [portName]\"")
        return
    }

    if (proto) {
        protoDownload(portName, family)
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
        if (protocol is SuuntoVyper2Protocol) {
            dumpVyper2Memory(protocol)
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

private fun downloadDives(family: SuuntoFamily, protocol: DiveComputerProtocol) {
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
 * Read the whole Vyper2-family memory over ReadMemory and save it as a .bin, for
 * fixtures and for checking the layout against what the device screen shows. Low
 * addresses may not answer, so a failed page is filled with 0xFF and the dump keeps
 * going; the saved .bin stays address-aligned.
 */
private fun dumpVyper2Memory(protocol: SuuntoVyper2Protocol) {
    val info = runCatching { protocol.readDeviceInfo() }.getOrNull()
    if (info != null) println("Device: ${info.vendor} ${info.model} firmware ${info.firmware}")

    val out = ByteArrayOutputStream()
    var addr = 0
    var failed = 0
    while (addr < DUMP_END) {
        val n = minOf(SuuntoVyper2Link.MAX_PAGE, DUMP_END - addr)
        val page = runCatching { protocol.link.readMemory(addr, n) }.getOrNull()
        if (page != null) {
            out.write(page)
        } else {
            out.write(ByteArray(n) { 0xFF.toByte() })
            failed++
            println("  page 0x${addr.toString(16)} did not answer, filled with ff")
        }
        addr += n
        if (addr % 0xC00 == 0) println("  $addr / $DUMP_END bytes (0x${addr.toString(16)})")
    }
    println("Done: $addr bytes, $failed page(s) unreadable")

    val dump = out.toByteArray()
    if (dump.all { it == 0xFF.toByte() }) {
        println("Every page was unreadable; not saving.")
        return
    }
    val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    val bin = File(dir, "suunto-vyper2-dump-${System.currentTimeMillis()}.bin")
    runCatching { bin.writeBytes(dump) }
        .onSuccess { println("Memory dump saved: ${bin.absolutePath} (${dump.size} bytes)") }
}

private fun saveTranscript(recording: RecordingTransport) {
    val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
    val file = File(dir, "suunto-capture-${System.currentTimeMillis()}.transcript.txt")
    runCatching { file.writeText(recording.transcript().toText()) }
        .onSuccess { println("Transcript saved: ${file.absolutePath}") }
}

/** Size of the Vyper2-family memory. */
private const val DUMP_END = 0x8000

/** Run the shared download over a bare transport, as the apps do, and print the parsed dives. */
private fun protoDownload(portName: String, family: SuuntoFamily) {
    val start = System.currentTimeMillis()
    fun elapsed() = (System.currentTimeMillis() - start) / 1000
    println("Line settings: ${family.serialParams}")
    val transport = JSerialCommTransport.byName(portName, family.serialParams)
    try {
        transport.open()
        val protocol = family.protocol(transport)
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model} serial ${info.serial} fw ${info.firmware}")
            override fun onDiveDownloaded(index: Int) = println("  dive ${index + 1} read, ${elapsed()}s")
        }
        val raw = protocol.download(null, listener, CancellationSignal.NONE)
        println("Downloaded ${raw.size} raw dive(s) in ${elapsed()}s")
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
