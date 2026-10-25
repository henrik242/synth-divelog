package no.synth.divelog.desktop

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerKind
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.MacBleScanner
import no.synth.divelog.core.transport.MacBleTransport
import no.synth.divelog.core.transport.MacRfcommTransport
import java.time.Instant

/**
 * Command-line Shearwater download over the macOS Bluetooth bridges, to test the link
 * and protocol without the app. A Petrel, Petrel 2 or Predator is picked from the paired
 * serial-port devices, a Peregrine (or a Petrel 2 with LE) from a Bluetooth LE scan; it then
 * downloads the latest dives and prints them parsed.
 *
 * Run: ./gradlew :app:desktop:shearwaterCapture --args="[PETREL|PETREL2|PREDATOR|PEREGRINE] [LE] [name or id] [dive count]"
 * Defaults: PETREL, the device whose name contains the model, 3 dives.
 */
fun main(args: Array<String>) {
    val words = args.map { it.uppercase() }
    val kind = when {
        "PREDATOR" in words -> DiveComputerKind.SHEARWATER_PREDATOR
        "PEREGRINE" in words -> DiveComputerKind.SHEARWATER_PEREGRINE
        "PETREL2" in words -> DiveComputerKind.SHEARWATER_PETREL_2
        else -> DiveComputerKind.SHEARWATER_PETREL
    }
    val model = kind.displayName.removePrefix("Shearwater ")
    val count = args.firstNotNullOfOrNull { it.toIntOrNull() } ?: 3
    val target = args.firstOrNull { it.uppercase() !in KEYWORDS && it.toIntOrNull() == null }
    val overLe = kind == DiveComputerKind.SHEARWATER_PEREGRINE || "LE" in words
    // Paired and advertised names carry the model's first word ("Petrel" for a Petrel 2).
    val name = target ?: model.substringBefore(' ')
    val transport = (if (overLe) bleTransport(name, explicit = target != null) else sppTransport(name)) ?: return

    val start = System.currentTimeMillis()
    fun elapsed() = "%.1fs".format((System.currentTimeMillis() - start) / 1000.0)
    println("Connecting as $model; put it in download mode")
    try {
        transport.open()
        println("Connected after ${elapsed()}")
        val protocol = kind.protocol(transport)
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model}")
            override fun onDiveCount(total: Int) = println("Downloading $total dive(s)")
            override fun onDiveDownloaded(index: Int, dive: RawDive) = println("  dive ${index + 1} read, ${elapsed()}")
        }
        val raws = protocol.download(null, listener, CancellationSignal.NONE, limit = count)
        println("Downloaded ${raws.size} raw dive(s) in ${elapsed()}")
        val parser = kind.parser()
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

private val KEYWORDS = setOf("PETREL", "PETREL2", "PREDATOR", "PEREGRINE", "LE")

/** The paired serial-port device addressed [target], or whose name contains it, through rfcomm-bridge. */
private fun sppTransport(target: String): Transport? {
    val bridge = MacRfcommTransport.locateBridge()
        ?: return null.also { println("No rfcomm-bridge found (macOS only; run through Gradle so it gets built).") }
    val paired = MacRfcommTransport.pairedDevices(bridge)
    println("Paired serial-port devices: ${if (paired.isEmpty()) "(none)" else paired.joinToString { "${it.name} ${it.address}" }}")
    val device = paired.firstOrNull { it.address.equals(target, ignoreCase = true) || it.name.contains(target, ignoreCase = true) }
        ?: return null.also { println("No paired device matches \"$target\".") }
    println("Using ${device.name} (${device.address})")
    return MacRfcommTransport(bridge, device.address)
}

/**
 * The computer a Bluetooth LE scan finds with id [target] or a name containing it, through
 * ble-bridge. Unless the target is [explicit] (only the model), a nameless one counts too.
 */
private fun bleTransport(target: String, explicit: Boolean): Transport? {
    val bridge = MacBleScanner.locateBridge()
        ?: return null.also { println("No ble-bridge found (macOS only; run through Gradle so it gets built).") }
    val scanner = MacBleScanner(bridge)
    println("Scanning for $target; start Bluetooth on it")
    scanner.scan()
    do {
        val device = scanner.found().firstOrNull {
            it.id.equals(target, ignoreCase = true) || it.name?.contains(target, ignoreCase = true) ?: !explicit
        }
        if (device != null) {
            scanner.stop()
            println("Using ${device.name ?: "(no name)"} (${device.id})")
            return MacBleTransport(bridge, device.id)
        }
        Thread.sleep(250)
    } while (scanner.scanning)
    scanner.stop()
    println("Nothing found for $target${scanner.problem()?.let { ": $it" } ?: ""}")
    return null
}
