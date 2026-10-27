package no.synth.divelog.ui.dive

import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device

/** Short source name, see [ComputerNames.displayName]. Null means a record with no device. */
fun deviceName(device: Device?): String = device?.let(ComputerNames::displayName) ?: ComputerNames.UNKNOWN

/** [deviceName] plus the serial when there is one, for the detail view. */
fun deviceDescription(device: Device?): String {
    val serial = device?.serial?.takeIf { it.isNotBlank() } ?: return deviceName(device)
    return "${deviceName(device)} (S/N $serial)"
}
