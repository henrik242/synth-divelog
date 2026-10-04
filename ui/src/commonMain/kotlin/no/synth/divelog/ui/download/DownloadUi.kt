package no.synth.divelog.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp

/** State machine for the shared download affordance driven from the Download button. */
sealed interface DownloadUiState {
    data object Hidden : DownloadUiState

    /** The serial picker: choose the dive-computer type and the port. */
    data object Picker : DownloadUiState

    /** A serial download is running. */
    data class Running(val fraction: Float, val label: String) : DownloadUiState

    /** A downloaded dive overlaps an existing one; [onResolve] attaches it (true) or keeps it separate. */
    data class Reviewing(val review: MergeReview, val onResolve: (Boolean) -> Unit) : DownloadUiState
}

/**
 * Pick the dive-computer type and the port, then start the download. Shared across
 * desktop (jSerialComm ports, including a paired Shearwater SPP port) and Android
 * (USB-serial adapters and paired Bluetooth Classic devices). [types] is what the
 * platform can read over a serial port.
 */
@Composable
fun DownloadPickerDialog(
    types: List<DiveComputerType>,
    ports: List<SerialPortInfo>,
    onRefresh: () -> List<SerialPortInfo>,
    onStart: (type: DiveComputerType, portId: String) -> Unit,
    onCancel: () -> Unit,
) {
    var type by remember { mutableStateOf(types.first()) }
    var current by remember { mutableStateOf(ports) }
    var port by remember { mutableStateOf(preferredPort(ports)) }
    var portMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Download dives") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dive computer", style = MaterialTheme.typography.labelLarge)
                types.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { type = t },
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.displayName)
                    }
                }

                Text("Port", style = MaterialTheme.typography.labelLarge)
                if (current.isEmpty()) {
                    Text("No ports found. Connect the cable, then refresh.")
                } else {
                    Box {
                        OutlinedButton(onClick = { portMenuOpen = true }) {
                            Text(port?.label ?: "Choose a port")
                        }
                        DropdownMenu(expanded = portMenuOpen, onDismissRequest = { portMenuOpen = false }) {
                            current.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.label) },
                                    onClick = { port = p; portMenuOpen = false },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    current = onRefresh()
                    if (port == null || current.none { it.id == port?.id }) port = preferredPort(current)
                }) { Text("Refresh ports") }
            }
        },
        confirmButton = {
            TextButton(enabled = port != null, onClick = { port?.let { onStart(type, it.id) } }) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Modal progress while a serial download runs, with a cancel action. */
@Composable
fun DownloadProgressDialog(state: DownloadUiState.Running, onCancel: () -> Unit) {
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

/**
 * Asks whether a downloaded dive that overlaps an existing one is the same dive from
 * another computer (attach it) or a separate dive (keep it). Shown mid-download, so it
 * has no dismiss: the user must choose before the import continues.
 */
@Composable
fun DownloadReviewDialog(state: DownloadUiState.Reviewing) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Merge dive?") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This download overlaps an existing dive, likely the same dive from another computer.")
                Text("Incoming:  ${state.review.incomingLabel}", style = MaterialTheme.typography.bodyMedium)
                Text("Existing:  ${state.review.existingLabel}", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = { state.onResolve(true) }) { Text("Merge into existing dive") } },
        dismissButton = { TextButton(onClick = { state.onResolve(false) }) { Text("Keep as separate dive") } },
    )
}
