package no.synth.divelog.desktop

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPetrelProtocol
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPredatorProtocol
import no.synth.divelog.core.transport.MacRfcommTransport
import java.time.Instant

/**
 * Command-line Shearwater download over the macOS Bluetooth bridge, to test the link
 * and protocol without the app. Lists the paired serial-port devices, connects to the
 * chosen one, downloads the latest dives and prints them parsed.
 *
 * Run: ./gradlew :app:desktop:shearwaterCapture --args="[PETREL|PREDATOR] [name or address] [dive count]"
 * Defaults: PETREL, the paired device whose name matches the model, 3 dives.
 */
fun main(args: Array<String>) {
    val bridge = MacRfcommTransport.locateBridge()
    if (bridge == null) {
        println("No rfcomm-bridge found (macOS only; run through Gradle so it gets built).")
        return
    }
    val paired = MacRfcommTransport.pairedDevices(bridge)
    println("Paired serial-port devices: ${if (paired.isEmpty()) "(none)" else paired.joinToString { "${it.name} ${it.address}" }}")

    val predator = args.any { it.equals("PREDATOR", ignoreCase = true) }
    val model = if (predator) "Predator" else "Petrel"
    val count = args.firstNotNullOfOrNull { it.toIntOrNull() } ?: 3
    val target = args.firstOrNull { it.uppercase() !in setOf("PETREL", "PREDATOR") && it.toIntOrNull() == null } ?: model
    val device = paired.firstOrNull { it.address.equals(target, ignoreCase = true) || it.name.equals(target, ignoreCase = true) }
    if (device == null) {
        println("No paired device matches \"$target\".")
        return
    }

    val start = System.currentTimeMillis()
    fun elapsed() = "%.1fs".format((System.currentTimeMillis() - start) / 1000.0)
    println("Connecting to ${device.name} (${device.address}) as $model; put it in download mode")
    val transport = MacRfcommTransport(bridge, device.address)
    try {
        transport.open()
        println("Connected after ${elapsed()}")
        val protocol = if (predator) ShearwaterPredatorProtocol(transport) else ShearwaterPetrelProtocol(transport)
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model}")
            override fun onDiveCount(total: Int) = println("Downloading $total dive(s)")
            override fun onDiveDownloaded(index: Int) = println("  dive ${index + 1} read, ${elapsed()}")
        }
        val raws = protocol.download(null, listener, CancellationSignal.NONE, limit = count)
        println("Downloaded ${raws.size} raw dive(s) in ${elapsed()}")
        val parser = PredatorParser()
        raws.forEachIndexed { i, raw ->
            runCatching { parser.parse(raw) }
                .onSuccess { d -> println("  #${i + 1}  ${Instant.ofEpochSecond(d.startEpochSeconds)}  ${d.durationSeconds / 60}min  max=${(d.maxDepthMm ?: 0) / 1000.0}m  ${d.samples.size} samples") }
                .onFailure { println("  #${i + 1}  parse failed: ${it.message}") }
        }
    } catch (e: Exception) {
        println("Download failed after ${elapsed()}: ${e.message}")
    } finally {
        transport.close()
    }
}
