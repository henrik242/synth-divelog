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
import no.synth.divelog.core.transport.ScannedDevice
import java.time.Instant

/**
 * Command-line Shearwater download over the macOS Bluetooth bridges, to test the link
 * and protocol without the app. A Petrel, Petrel 2 or Predator is picked from the paired
 * serial-port devices, a Peregrine, Petrel 3 (or Petrel 2 with LE) from a Bluetooth LE scan;
 * it then downloads the latest dives and prints them parsed.
 *
 * Run: ./gradlew :app:desktop:shearwaterCapture --args="[PETREL|PETREL2|PETREL3|PREDATOR|PEREGRINE] [LE] [name or id] [dive count]"
 * Defaults: PETREL, the device whose name contains the model, 3 dives.
 */
fun main(args: Array<String>) {
    val words = args.map { it.uppercase() }
    val kind = KINDS.entries.firstOrNull { it.key in words }?.value ?: DiveComputerKind.SHEARWATER_PETREL
    val model = kind.displayName.removePrefix("Shearwater ")
    val count = args.firstNotNullOfOrNull { it.toIntOrNull() } ?: 3
    val target = args.zip(words).firstOrNull { (_, w) -> w !in KINDS && w != "LE" && w.toIntOrNull() == null }?.first
    val overLe = kind.bleName != null && (kind in BLE_ONLY || "LE" in words)
    if ("LE" in words && !overLe) return println("$model has no Bluetooth LE.")
    val transport = when {
        overLe -> bleTransport(target, kind)
        else -> sppTransport(target ?: model)
    } ?: return

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

private val KINDS = mapOf(
    "PREDATOR" to DiveComputerKind.SHEARWATER_PREDATOR,
    "PEREGRINE" to DiveComputerKind.SHEARWATER_PEREGRINE,
    "PETREL2" to DiveComputerKind.SHEARWATER_PETREL_2,
    "PETREL3" to DiveComputerKind.SHEARWATER_PETREL_3,
    "PETREL" to DiveComputerKind.SHEARWATER_PETREL,
)

private val BLE_ONLY = setOf(DiveComputerKind.SHEARWATER_PEREGRINE, DiveComputerKind.SHEARWATER_PETREL_3)

/** The paired serial-port device addressed or named [target], else whose name contains it, through rfcomm-bridge. */
private fun sppTransport(target: String): Transport? {
    val bridge = MacRfcommTransport.locateBridge()
        ?: return null.also { println("No rfcomm-bridge found (macOS only; run through Gradle so it gets built).") }
    val paired = MacRfcommTransport.pairedDevices(bridge)
    println("Paired serial-port devices: ${if (paired.isEmpty()) "(none)" else paired.joinToString { "${it.name} ${it.address}" }}")
    val device = paired.firstOrNull { it.address.equals(target, ignoreCase = true) || it.name.equals(target, ignoreCase = true) }
        ?: paired.firstOrNull { it.name.contains(target, ignoreCase = true) }
        ?: return null.also { println("No paired device matches \"$target\".") }
    println("Using ${device.name} (${device.address})")
    return MacRfcommTransport(bridge, device.address)
}

/**
 * The computer a Bluetooth LE scan finds with id [target] or a name containing it, through
 * ble-bridge; with no target, one whose name says it is a [kind] (as the app's picker
 * decides), or a nameless one.
 */
private fun bleTransport(target: String?, kind: DiveComputerKind): Transport? {
    val bridge = MacBleScanner.locateBridge()
        ?: return null.also { println("No ble-bridge found (macOS only; run through Gradle so it gets built).") }
    val scanner = MacBleScanner(bridge)
    val wanted = target ?: kind.displayName
    fun matches(d: ScannedDevice): Boolean = when {
        target != null -> d.id.equals(target, ignoreCase = true) || d.name?.contains(target, ignoreCase = true) == true
        else -> d.name?.let { DiveComputerKind.forAdvertisedName(it) == kind } ?: true
    }
    println("Scanning for $wanted; start Bluetooth on it")
    scanner.scan()
    do {
        val device = scanner.found().firstOrNull(::matches)
        if (device != null) {
            scanner.stop()
            println("Using ${device.name ?: "(no name)"} (${device.id})")
            return MacBleTransport(bridge, device.id)
        }
        Thread.sleep(250)
    } while (scanner.scanning)
    scanner.stop()
    println("Nothing found for $wanted${scanner.problem()?.let { ": $it" } ?: ""}")
    return null
}
