package no.synth.divelog.desktop

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerKind
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Link
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Protocol
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.divecomputer.transport.Transport
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

    val familyArg = args.firstNotNullOfOrNull(::suuntoFamily)
    val proto = args.any { it.equals("proto", ignoreCase = true) }
    val portArg = args.firstOrNull { !it.equals("proto", ignoreCase = true) && suuntoFamily(it) == null }

    val family = familyArg ?: DiveComputerKind.SUUNTO_VYPER
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
        if (family == DiveComputerKind.SUUNTO_VYPER2) {
            dumpVyper2Memory(recording)
        } else {
            downloadDives(family, family.protocol(recording))
        }
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace()
    } finally {
        saveTranscript(recording)
        runCatching { recording.close() }
    }
}

/** VYPER or VYPER2 (or the full SUUNTO_ name) to the Suunto family, else null. */
private fun suuntoFamily(arg: String): DiveComputerKind? = when (arg.uppercase().removePrefix("SUUNTO_")) {
    "VYPER" -> DiveComputerKind.SUUNTO_VYPER
    "VYPER2" -> DiveComputerKind.SUUNTO_VYPER2
    else -> null
}

private fun downloadDives(family: DiveComputerKind, protocol: DiveComputerProtocol) {
    val listener = object : DownloadListener {
        override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model}")
        override fun onDiveDownloaded(index: Int, dive: RawDive) = println("  downloaded dive ${index + 1}")
    }
    val raw = protocol.download(null, listener, CancellationSignal.NONE)
    println("Downloaded ${raw.size} raw dive(s)")
    val parser = family.parser()
    raw.forEach { r ->
        runCatching { parser.parse(r) }
            .onSuccess { d ->
                println("  dive #${d.number ?: "?"}  max=${d.maxDepthMm ?: 0} mm  ${d.durationSeconds}s  ${d.samples.size} samples")
            }
            .onFailure { println("  parse failed: ${it.message}") }
    }
}

/**
 * Read the whole Vyper2-family memory over ReadMemory and save it as a .bin, for
 * fixtures and for checking the layout against what the device screen shows. Low
 * addresses may not answer, so a failed page is filled with 0xFF and the dump keeps
 * going; the saved .bin stays address-aligned.
 */
private fun dumpVyper2Memory(transport: Transport) {
    val info = runCatching { SuuntoVyper2Protocol(transport).readDeviceInfo() }.getOrNull()
    val link = SuuntoVyper2Link(transport)
    if (info != null) println("Device: ${info.vendor} ${info.model} firmware ${info.firmware}")

    val out = ByteArrayOutputStream()
    var addr = 0
    var failed = 0
    while (addr < DUMP_END) {
        val n = minOf(SuuntoVyper2Link.MAX_PAGE, DUMP_END - addr)
        val page = runCatching { link.readMemory(addr, n) }.getOrNull()
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
private fun protoDownload(portName: String, family: DiveComputerKind) {
    val start = System.currentTimeMillis()
    fun elapsed() = (System.currentTimeMillis() - start) / 1000
    println("Line settings: ${family.serialParams}")
    val transport = JSerialCommTransport.byName(portName, family.serialParams)
    try {
        transport.open()
        val protocol = family.protocol(transport)
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model} serial ${info.serial} fw ${info.firmware}")
            override fun onDiveDownloaded(index: Int, dive: RawDive) = println("  dive ${index + 1} read, ${elapsed()}s")
        }
        val raw = protocol.download(null, listener, CancellationSignal.NONE)
        println("Downloaded ${raw.size} raw dive(s) in ${elapsed()}s")
        val parser = family.parser()
        raw.forEachIndexed { i, r ->
            runCatching { parser.parse(r) }
                .onSuccess { d -> println("  #${i + 1}  ${Instant.ofEpochSecond(d.startEpochSeconds)}  ${d.durationSeconds / 60}min  max=${(d.maxDepthMm ?: 0) / 1000.0}m  ${d.samples.size} samples") }
                .onFailure { println("  #${i + 1}  parse failed: ${it.message}") }
        }
    } catch (e: Exception) {
        println("proto download failed: ${e.message}")
    } finally {
        runCatching { transport.close() }
    }
}
