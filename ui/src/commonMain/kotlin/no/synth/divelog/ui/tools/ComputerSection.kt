package no.synth.divelog.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.ui.common.observe

/**
 * The downloaded computer [item] is, with merge and forget; or, for an unlinked dive
 * computer, a way to link it to one. [onRelinked] runs after linking or forgetting changed
 * [item]; [onGone] after merging moved it into another computer's item.
 */
@Composable
internal fun ComputerSection(container: AppContainer, item: GearItem, onRelinked: () -> Unit, onGone: () -> Unit) {
    if (item.deviceId == null && item.kind != GearKind.COMPUTER) return
    val devices = observe(container, read = container.devices::all, flow = container.devices::allFlow)
    val diveCounts = observe(container, read = container.devices::diveCounts, flow = container.devices::diveCountsFlow)
    var dialog by remember(item.id) { mutableStateOf<ComputerDialog?>(null) }
    val device = devices.firstOrNull { it.id == item.deviceId }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Dive computer", style = MaterialTheme.typography.titleSmall)
        if (device == null) {
            Text(
                "Not linked to a computer you have downloaded from or imported dives of.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (devices.isNotEmpty()) {
                OutlinedButton(onClick = { dialog = ComputerDialog.LINK }) { Text("Link to downloaded computer...") }
            }
        } else {
            Text(device.details(), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.size > 1) OutlinedButton(onClick = { dialog = ComputerDialog.MERGE }) { Text("Merge into...") }
                OutlinedButton(
                    onClick = { dialog = ComputerDialog.FORGET },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Forget") }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        ComputerDialog.LINK -> PickComputerDialog(
            title = "Link ${item.name} to",
            text = "Combines this item with a computer you have downloaded from. Its services and dives " +
                "move here, and this name is kept.",
            confirm = "Link",
            choices = devices,
            diveCounts = diveCounts,
            onConfirm = { container.gear.linkToDevice(item.id, it.id); close(); onRelinked() },
            onDismiss = close,
        )
        ComputerDialog.MERGE -> if (device != null) PickComputerDialog(
            title = "Merge ${item.name} into",
            text = "Moves all dives from this computer to the one you pick, then removes this one. " +
                "The one you pick keeps its name.",
            confirm = "Merge",
            choices = devices.filter { it.id != device.id },
            diveCounts = diveCounts,
            onConfirm = { container.devices.merge(device.id, it.id); close(); onGone() },
            onDismiss = close,
        )
        ComputerDialog.FORGET -> if (device != null) AlertDialog(
            onDismissRequest = close,
            title = { Text("Forget ${item.name}?") },
            text = {
                Text(
                    "The computer is removed. This gear item stays, with its services, but is no longer linked. " +
                        "The dives keep their logs, without a computer. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { container.devices.forget(device.id); close(); onRelinked() }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        null -> {}
    }
}

private enum class ComputerDialog { LINK, MERGE, FORGET }

/**
 * "Shearwater Perdix - Serial 123 - Firmware 90", and "- 12 dives" with [dives]; the gear
 * page has its own "Used in" count, the pickers do not.
 */
private fun Device.details(dives: Long? = null): String = buildList {
    add(ComputerNames.fullName(this@details))
    serial?.takeIf { it.isNotBlank() }?.let { add("Serial $it") }
    firmware?.takeIf { it.isNotBlank() }?.let { add("Firmware $it") }
    dives?.let { add(if (it == 1L) "1 dive" else "$it dives") }
}.joinToString(" - ")

@Composable
private fun PickComputerDialog(
    title: String,
    text: String,
    confirm: String,
    choices: List<Device>,
    diveCounts: Map<Long, Long>,
    onConfirm: (Device) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf<Device?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                choices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen?.id == d.id, role = Role.RadioButton, onClick = { chosen = d }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen?.id == d.id, onClick = null)
                        Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                            Text(ComputerNames.displayName(d), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                d.details(diveCounts[d.id] ?: 0L),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { chosen?.let(onConfirm) }, enabled = chosen != null) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
