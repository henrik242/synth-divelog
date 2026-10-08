package no.synth.divelog.ui.dive

import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device

/** Short source name: nickname if set, else "vendor model". Null means no device (file import). */
fun deviceName(device: Device?): String {
    if (device == null) return "Imported from file"
    val nick = device.nickname?.takeIf { it.isNotBlank() }
    return nick ?: ComputerNames.fullName(device)
}

/** [deviceName] plus the serial when there is one, for the detail view. */
fun deviceDescription(device: Device?): String {
    val serial = device?.serial?.takeIf { it.isNotBlank() } ?: return deviceName(device)
    return "${deviceName(device)} (S/N $serial)"
}
