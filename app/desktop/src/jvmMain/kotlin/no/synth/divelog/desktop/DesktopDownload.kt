package no.synth.divelog.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.db.ImportResult
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Dump
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Parser
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.transport.JSerialCommTransport
import no.synth.divelog.ui.AppContainer

/** Progress of the desktop wired download, surfaced to the Compose UI. */
sealed interface DesktopDownloadState {
    data object Idle : DesktopDownloadState
    data class Running(val fraction: Float, val label: String) : DesktopDownloadState
}

/**
 * Auto-detect the USB-serial port for the HelO2/D9 family: prefer a name that looks
 * like a USB-serial adapter, else fall back to the sole port if there is exactly one.
 * Returns null when no candidate is present. A device/port picker is a follow-up.
 */
fun detectD9Port(): String? {
    val ports = JSerialCommTransport.availablePortNames()
    return ports.firstOrNull {
        it.contains("usbserial", true) || it.contains("tty.usb", true) || it.contains("ttyUSB", true)
    } ?: ports.singleOrNull()
}

/**
 * Run a HelO2/D9 wired download end to end: capture the memory over the proven raw
 * read path, walk and parse the dives, and import them through the shared pipeline so
 * dedupe, device identity and the data refresh match file import and the Android
 * download. Returns a user-facing summary. Blocking; call off the UI thread.
 */
fun downloadD9ToLogbook(
    container: AppContainer,
    isCancelled: () -> Boolean = { false },
    onProgress: (fraction: Float, label: String) -> Unit = { _, _ -> },
): String {
    val port = detectD9Port()
        ?: throw SuuntoCaptureException("No USB-serial dive computer found. Connect the HelO2/D9 cable and try again.")

    onProgress(0f, "Reading from the dive computer")
    val image = captureD9Image(port, isCancelled) { done, total ->
        onProgress(done.toFloat() / total, "Reading memory ${done * 100 / total}%")
    }

    onProgress(1f, "Parsing dives")
    val raws = extractD9Dives(image)

    val deviceId = container.devices.getOrCreate(deviceFor(image))
    val parser = SuuntoD9Parser()
    var imported = 0
    var skipped = 0
    for (raw in raws) {
        val incoming = runCatching { parser.parse(raw) }.getOrNull()?.copy(deviceId = deviceId) ?: continue
        when (container.dives.import(incoming) { false }) {
            is ImportResult.SkippedDuplicate -> skipped++
            else -> imported++
        }
    }

    return when {
        imported > 0 ->
            "Imported $imported new dive${plural(imported)}" +
                if (skipped > 0) ", skipped $skipped already in the logbook" else ""
        skipped > 0 -> "No new dives ($skipped already in the logbook)"
        raws.isEmpty() -> "No dives found on the device"
        else -> "No new dives"
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

/**
 * Stable identity for the connected computer so repeated downloads dedupe against the
 * same device row (dive dedupe keys on device plus fingerprint). Uses the serial read
 * from the image when it is present, falling back to a fixed tag for the single
 * USB-serial device. The synthetic address is just the identity key getOrCreate dedupes
 * on; there is no Bluetooth here.
 */
private fun deviceFor(image: ByteArray): Device {
    val serial = image.copyOfRange(SuuntoD9Dump.SERIAL_OFFSET, SuuntoD9Dump.SERIAL_OFFSET + SuuntoD9Dump.SERIAL_SIZE)
    val readable = serial.any { it != 0xFF.toByte() && it != 0x00.toByte() }
    val serialHex = if (readable) serial.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } else null
    return Device(
        vendor = "Suunto",
        model = "HelO2 / D9",
        serial = serialHex,
        bluetoothAddress = "usb-serial:suunto-d9" + (serialHex?.let { ":$it" } ?: ""),
    )
}

/** Modal progress while a wired download runs, with a cancel action. */
@Composable
fun DesktopDownloadDialog(state: DesktopDownloadState.Running, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Downloading dives") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.label)
                if (state.fraction > 0f) {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
