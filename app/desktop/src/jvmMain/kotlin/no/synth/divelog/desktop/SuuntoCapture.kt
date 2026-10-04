package no.synth.divelog.desktop

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

    // An arg that names a family is the family; anything else is the port name.
    // This way "D9" alone selects the family and lets the port auto-detect.
    val familyArg = args.firstNotNullOfOrNull { runCatching { SuuntoFamily.valueOf(it.uppercase()) }.getOrNull() }
    val portArg = args.firstOrNull { runCatching { SuuntoFamily.valueOf(it.uppercase()) }.isFailure }

    val family = familyArg ?: SuuntoFamily.VYPER
    val portName = portArg
        ?: ports.firstOrNull { it.contains("usbserial", true) || it.contains("tty.usb", true) || it.contains("ttyUSB", true) }
    if (portName == null) {
        println("No port given and no USB-serial port auto-detected.")
        println("Usage: ./gradlew :app:desktop:suuntoCapture --args=\"[VYPER|D9] [portName]\"")
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
