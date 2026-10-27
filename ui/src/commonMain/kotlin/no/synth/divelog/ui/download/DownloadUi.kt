package no.synth.divelog.ui.download

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.logbook.download.DiveAction
import no.synth.divelog.core.logbook.download.DiveComputerType
import no.synth.divelog.core.logbook.download.DownloadAmount
import no.synth.divelog.core.logbook.download.DownloadedDive
import no.synth.divelog.core.logbook.download.KnownComputer
import no.synth.divelog.core.logbook.download.SerialPortInfo
import no.synth.divelog.core.logbook.download.SerialPorts
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.ui.components.SelectField
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** State machine for the shared download affordance driven from the Download button. */
sealed interface DownloadUiState {
    data object Hidden : DownloadUiState

    /** The serial picker: choose the dive-computer type and the port. */
    data object Picker : DownloadUiState

    /** A serial download is running. */
    data class Running(val fraction: Float, val label: String) : DownloadUiState

    /** The download has ended; [onResolve] gets an action per entry of [dives] (see [DownloadReviewDialog]). */
    data class Reviewing(val dives: List<DownloadedDive>, val onResolve: (List<DiveAction>) -> Unit) : DownloadUiState
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
 * The selected computer shows its [hint], if any, above the port. [ports] are the ones
 * [serialPorts] listed when the picker opened.
 * Shared across desktop (wired ports and paired Bluetooth devices) and Android (USB-serial
 * adapters, paired Bluetooth Classic devices and scanned Bluetooth LE computers).
 */
@Composable
fun DownloadPickerDialog(
    types: List<DiveComputerType>,
    known: List<KnownComputer>,
    lastKey: String?,
    serialPorts: SerialPorts,
    ports: List<SerialPortInfo>,
    onStart: (type: DiveComputerType, port: SerialPortInfo, amount: DownloadAmount) -> Unit,
    onCancel: () -> Unit,
    preselectPortId: (DiveComputerType, List<SerialPortInfo>) -> String? = { _, _ -> null },
    hint: (DiveComputerType) -> String? = { null },
) {
    val scope = rememberCoroutineScope()
    // A known computer's own last port, else the port a device of this type was last
    // reached on, else a sensible guess; only among the ports the type can use.
    fun pick(choice: PickerChoice, all: List<SerialPortInfo>): SerialPortInfo? {
        val list = all.filter { choice.type.canUse(it) }
        val own = (choice as? PickerChoice.Known)?.computer?.lastPort?.let { d -> list.firstOrNull { it.descriptor == d } }
        return own
            ?: preselectPortId(choice.type, list)?.let { id -> list.firstOrNull { it.id == id } }
            ?: choice.type.likelyPort(list)
    }

    val initial: PickerChoice = (known.firstOrNull { it.key == lastKey } ?: known.firstOrNull())
        ?.let { PickerChoice.Known(it) }
        ?: PickerChoice.Other(types.first())
    var choice by remember { mutableStateOf(initial) }
    var current by remember { mutableStateOf(ports) }
    var port by remember { mutableStateOf(pick(initial, ports)) }
    var amount by remember { mutableStateOf<DownloadAmount>(DownloadAmount.NewOnly) }
    // Until the user picks a port, a better one a scan turns up (the remembered computer) replaces the guess.
    var userPicked by remember { mutableStateOf(false) }

    var problem by remember { mutableStateOf<String?>(null) }
    // Whether a scan has run a while without finding the selected computer.
    var searchedLong by remember { mutableStateOf(false) }

    fun select(c: PickerChoice) {
        choice = c
        userPicked = false
        port = pick(c, current)
    }

    fun show(ports: List<SerialPortInfo>) {
        current = ports
        if (!userPicked || ports.none { it.id == port?.id }) port = pick(choice, ports)
    }

    // Listing can be slow (the macOS Bluetooth helper), so it runs off the main thread.
    suspend fun refresh() = show(withContext(Dispatchers.IO) { serialPorts.list() })

    // A computer found by scanning shows up whenever the scan sees it, so keep the scan
    // running and the list current while one is selected; no Refresh tapping needed. The
    // scan stops with the picker or another selection.
    LaunchedEffect(choice.type) {
        problem = null
        searchedLong = false
        if (!choice.type.foundByScan(serialPorts.linkKinds)) return@LaunchedEffect
        val started = TimeSource.Monotonic.markNow()
        try {
            while (true) {
                serialPorts.discover()
                refresh()
                problem = serialPorts.scanProblem()
                searchedLong = started.elapsedNow() >= SEARCH_HINT_AFTER
                delay(SCAN_REFRESH_MS)
            }
        } finally {
            serialPorts.stopDiscovery()
        }
    }

    // Port and download amount, shown inside the selected row.
    @Composable
    fun ComputerOptions() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        hint(choice.type)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val usable = current.filter { choice.type.canUse(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (usable.isEmpty()) {
                val empty = if (choice.type.foundByScan(serialPorts.linkKinds)) {
                    problem ?: if (searchedLong) {
                        "Not found yet. Is Bluetooth started in the computer's menu, and the computer close by?"
                    } else {
                        "Searching for the dive computer..."
                    }
                } else {
                    "No ports found; connect the cable"
                }
                Text(empty, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            } else {
                SelectField(
                    label = "Port",
                    value = port?.label ?: "Choose a port",
                    options = usable,
                    optionLabel = { it.label },
                    onSelect = {
                        port = it
                        userPicked = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
        }
        // Listed ports (a paired device) do not mean the scan works; say why it may not.
        if (usable.isNotEmpty()) {
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

/** How often the picker refreshes the list while a scanned computer is selected. */
private const val SCAN_REFRESH_MS = 2_000L

/** How long a scan runs before the picker asks whether the computer is ready. */
private val SEARCH_HINT_AFTER = 25.seconds

/** What to do on the computer itself before downloading from it, on every platform. */
fun connectHint(type: DiveComputerType): String = when (type) {
    DiveComputerType.SUUNTO_VYPER, DiveComputerType.SUUNTO_VYPER2 -> "Connect the Suunto USB cable."
    DiveComputerType.SHEARWATER_PETREL, DiveComputerType.SHEARWATER_PREDATOR ->
        "Pair the computer in the system's Bluetooth settings, then start Bluetooth from its menu."
    DiveComputerType.SHEARWATER -> "Start Bluetooth from the computer's menu and keep it close by."
}
