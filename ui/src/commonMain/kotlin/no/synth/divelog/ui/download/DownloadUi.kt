package no.synth.divelog.ui.download

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import no.synth.divelog.ui.format.Format

/** State machine for the shared download affordance driven from the Download button. */
sealed interface DownloadUiState {
    data object Hidden : DownloadUiState

    /** The serial picker: choose the dive-computer type and the port. */
    data object Picker : DownloadUiState

    /** A serial download is running. */
    data class Running(val fraction: Float, val label: String) : DownloadUiState

    /**
     * Downloaded dives overlap existing ones; [onResolve] gets the indices into [reviews]
     * to attach to their existing dive, the rest are kept separate.
     */
    data class Reviewing(val reviews: List<MergeReview>, val onResolve: (Set<Int>) -> Unit) : DownloadUiState
}

/** What the picker has selected: a computer already in the logbook, or just a type. */
private sealed interface PickerChoice {
    val type: DiveComputerType

    data class Known(val computer: KnownComputer) : PickerChoice {
        override val type: DiveComputerType get() = computer.type
    }

    data class Other(override val type: DiveComputerType) : PickerChoice
}

/**
 * Pick the dive computer and the port, then start the download. Computers already in the
 * logbook ([known]) are listed first with their dive count, newest dive and last port;
 * choosing one preselects the port it was last reached on. The computer used last
 * ([lastKey]) starts selected. Any [types] entry can still be picked for a new computer.
 * Shared across desktop (wired ports and paired Bluetooth devices) and Android (USB-serial
 * adapters and paired Bluetooth Classic devices).
 */
@Composable
fun DownloadPickerDialog(
    types: List<DiveComputerType>,
    known: List<KnownComputer>,
    lastKey: String?,
    ports: List<SerialPortInfo>,
    onRefresh: () -> List<SerialPortInfo>,
    onStart: (type: DiveComputerType, port: SerialPortInfo, amount: DownloadAmount) -> Unit,
    onCancel: () -> Unit,
    preselectPortId: (DiveComputerType) -> String? = { null },
) {
    // A known computer's own last port, else the port a device of this type was last
    // reached on, else a sensible guess.
    fun pick(choice: PickerChoice, list: List<SerialPortInfo>): SerialPortInfo? {
        val own = (choice as? PickerChoice.Known)?.computer?.lastPort?.let { d -> list.firstOrNull { it.descriptor == d } }
        return own
            ?: preselectPortId(choice.type)?.let { id -> list.firstOrNull { it.id == id } }
            ?: preferredPort(list)
    }

    val initial: PickerChoice = (known.firstOrNull { it.key == lastKey } ?: known.firstOrNull())
        ?.let { PickerChoice.Known(it) }
        ?: PickerChoice.Other(types.first())
    var choice by remember { mutableStateOf(initial) }
    var current by remember { mutableStateOf(ports) }
    var port by remember { mutableStateOf(pick(initial, ports)) }
    var amount by remember { mutableStateOf<DownloadAmount>(DownloadAmount.NewOnly) }

    fun select(c: PickerChoice) {
        choice = c
        port = pick(c, current)
    }

    // Port and download amount, shown inside the selected row.
    @Composable
    fun ComputerOptions() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current.isEmpty()) {
                Text("No ports found; connect the cable", style = MaterialTheme.typography.bodySmall)
            } else {
                SelectField(
                    label = "Port",
                    value = port?.label ?: "Choose a port",
                    options = current,
                    optionLabel = { it.label },
                    onSelect = { port = it },
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = {
                current = onRefresh()
                if (port == null || current.none { it.id == port?.id }) port = pick(choice, current)
            }) { Text("Refresh") }
        }
        SelectField(
            label = "Download",
            value = amount.label,
            options = DownloadAmount.options,
            optionLabel = { it.label },
            onSelect = { amount = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Download dives") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (known.isNotEmpty()) {
                    Text("Your dive computers", style = MaterialTheme.typography.labelLarge)
                    known.forEach { computer ->
                        val c = PickerChoice.Known(computer)
                        ChoiceRow(selected = choice == c, onSelect = { select(c) }, expanded = { ComputerOptions() }) {
                            Text(computer.title, style = MaterialTheme.typography.bodyLarge)
                            Text(computer.subtitle, style = MaterialTheme.typography.bodySmall)
                            Text(
                                knownDetails(computer, current),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    if (known.isEmpty()) "Dive computer" else "Another dive computer",
                    style = MaterialTheme.typography.labelLarge,
                )
                types.forEach { t ->
                    val c = PickerChoice.Other(t)
                    ChoiceRow(selected = choice == c, onSelect = { select(c) }, expanded = { ComputerOptions() }) {
                        Text(t.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = port != null, onClick = { port?.let { onStart(choice.type, it, amount) } }) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** A read-only dropdown field: shows [value] under [label] and opens a list of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SelectField(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

/** A selectable row; when [selected] it expands to show [expanded] under its label. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    onSelect: () -> Unit,
    expanded: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column { content() }
        }
        if (selected) {
            Box(Modifier.padding(start = 48.dp, bottom = 4.dp)) { expanded() }
        }
    }
}

/** "42 dives · newest 10 Apr 2025 · cu.usbserial-ST000001", naming the last port if it is gone. */
private fun knownDetails(computer: KnownComputer, ports: List<SerialPortInfo>): String = listOfNotNull(
    "${computer.diveCount} dive${if (computer.diveCount == 1L) "" else "s"}",
    computer.newestDive?.let { (epoch, offset) -> "newest ${Format.date(epoch, offset)}" },
    computer.lastPort?.let { d -> ports.firstOrNull { it.descriptor == d }?.label ?: "last on $d" },
).joinToString(" · ")

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
 * Lists the downloaded dives that overlap an existing dive, each likely the same dive
 * from another computer. Checked rows are attached to their existing dive, unchecked
 * ones are kept as separate dives. Shown mid-download, so it has no dismiss: the user
 * must choose before the import continues.
 */
@Composable
fun DownloadReviewDialog(state: DownloadUiState.Reviewing) {
    var checked by remember(state) { mutableStateOf(state.reviews.indices.toSet()) }
    val all = checked.size == state.reviews.size

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Merge dives?") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.reviews.size == 1) {
                        "This dive overlaps an existing dive, likely the same dive from another computer. " +
                            "Checked dives are merged into the existing one; unchecked are kept separate."
                    } else {
                        "These ${state.reviews.size} dives overlap existing dives, likely the same dives from " +
                            "another computer. Checked dives are merged into the existing one; unchecked are kept separate."
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        checked = if (all) emptySet() else state.reviews.indices.toSet()
                    },
                ) {
                    Checkbox(
                        checked = all,
                        onCheckedChange = { checked = if (it) state.reviews.indices.toSet() else emptySet() },
                    )
                    Text("Select all", style = MaterialTheme.typography.labelLarge)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(state.reviews) { i, review ->
                        fun toggle() {
                            checked = if (i in checked) checked - i else checked + i
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { toggle() },
                        ) {
                            Checkbox(checked = i in checked, onCheckedChange = { toggle() })
                            Column {
                                Text(review.incomingLabel, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "overlaps ${review.existingLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.onResolve(checked) }) {
                val separate = state.reviews.size - checked.size
                Text(
                    when {
                        checked.isEmpty() -> "Keep all separate"
                        separate == 0 -> "Merge all"
                        else -> "Merge ${checked.size}, keep $separate separate"
                    },
                )
            }
        },
    )
}
