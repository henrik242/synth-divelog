package no.synth.divelog.desktop

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.suunto.SuuntoFamily
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.transport.JSerialCommTransport
import java.io.File

/**
 * Command-line capture tool for a Suunto over the USB cable, for bring-up and
 * debugging before there is a desktop download UI. Opens the serial port, runs the
 * selected family's protocol through a RecordingTransport, prints the decoded dives
 * and saves the raw transcript (which can become a replay regression fixture).
 *
 * Run: ./gradlew :app:desktop:suuntoCapture --args="[portName] [VYPER|D9]"
 * With no port it lists the available ports and tries to auto-pick a USB-serial one.
 */
fun main(args: Array<String>) {
    val ports = JSerialCommTransport.availablePortNames()
    println("Available serial ports: ${if (ports.isEmpty()) "(none)" else ports.joinToString()}")

    val portName = args.getOrNull(0)
        ?: ports.firstOrNull { it.contains("usbserial", true) || it.contains("tty.usb", true) || it.contains("ttyUSB", true) }
    if (portName == null) {
        println("No port given and no USB-serial port auto-detected.")
        println("Usage: ./gradlew :app:desktop:suuntoCapture --args=\"<portName> [VYPER|D9]\"")
        return
    }
    val family = args.getOrNull(1)?.let { runCatching { SuuntoFamily.valueOf(it.uppercase()) }.getOrNull() }
        ?: SuuntoFamily.VYPER
    println("Opening $portName as ${family.displayName}")
    println("Line settings: ${family.serialParams}")

    val recording = RecordingTransport(JSerialCommTransport.byName(portName, family.serialParams)) {
        System.currentTimeMillis()
    }
    try {
        recording.open()
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model}")
            override fun onDiveDownloaded(index: Int) = println("  downloaded dive ${index + 1}")
        }
        val raw = family.protocol(recording).download(null, listener, CancellationSignal.NONE)
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
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace()
    } finally {
        val dir = File(System.getProperty("user.home"), ".synth-divelog").apply { mkdirs() }
        val file = File(dir, "suunto-capture-${System.currentTimeMillis()}.transcript.txt")
        runCatching { file.writeText(recording.transcript().toText()) }
            .onSuccess { println("Transcript saved: ${file.absolutePath}") }
        runCatching { recording.close() }
    }
}
