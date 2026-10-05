package no.synth.divelog.ui.dive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.format.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiveDetailScreen(
    container: AppContainer,
    diveId: Long,
    unitSystem: UnitSystem,
    reloadKey: Int = 0,
    orderedDiveIds: List<Long> = emptyList(),
    onNavigate: (Long) -> Unit = {},
    onOpenDevice: (Long) -> Unit = {},
    onEdit: () -> Unit = {},
    onChanged: () -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val dive = remember(diveId, reloadKey) { container.dives.getDive(diveId) } ?: run {
        Text("Dive not found", Modifier.padding(16.dp)); return
    }
    val records = remember(diveId, reloadKey) { container.dives.recordsForDive(diveId) }
    val devices = remember(records) {
        records.mapNotNull { it.deviceId }.distinct().associateWith { container.devices.get(it) }
    }
    val site = remember(diveId, reloadKey) { dive.siteId?.let { container.sites.site(it) } }
    val buddies = remember(diveId, reloadKey) { container.buddies.buddiesForDive(diveId) }

    // Previous/next follow the order of the list the user came from (index-1 / index+1).
    val index = orderedDiveIds.indexOf(diveId)
    val prevId = if (index > 0) orderedDiveIds[index - 1] else null
    val nextId = if (index >= 0 && index < orderedDiveIds.lastIndex) orderedDiveIds[index + 1] else null

    var selectedRecord by remember(diveId) {
        mutableStateOf(
            records.firstOrNull { it.id == dive.primaryComputerRecordId } ?: records.firstOrNull(),
        )
    }
    val samples = remember(selectedRecord?.id) {
        selectedRecord?.let { container.dives.samplesForRecord(it.id) } ?: emptyList()
    }
    val events = remember(selectedRecord?.id) {
        selectedRecord?.let { container.dives.eventsForRecord(it.id) } ?: emptyList()
    }

    var showMerge by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete dive?") },
            text = { Text("This removes the dive and its computer records.") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { container.dives.deleteDive(diveId); showDelete = false; onDeleted() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }

    if (showMerge) {
        val others = remember(reloadKey) { container.dives.allDives().filter { it.id != diveId } }
        AlertDialog(
            onDismissRequest = { showMerge = false },
            title = { Text("Merge another dive into this one") },
            text = {
                if (others.isEmpty()) {
                    Text("No other dives to merge.")
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(others) { other ->
                            Text(
                                text = (other.number?.let { "#$it  " } ?: "") +
                                    Format.dateTime(other.startEpochSeconds, other.utcOffsetSeconds),
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        container.dives.mergeDives(sourceDiveId = other.id, targetDiveId = diveId)
                                        showMerge = false
                                        onChanged()
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showMerge = false }) { Text("Cancel") } },
        )
    }

    // Horizontal swipe: left reveals the next dive, right the previous one.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    val swipeModifier = Modifier.pointerInput(diveId, orderedDiveIds) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total <= -swipeThresholdPx) nextId?.let(onNavigate)
                else if (total >= swipeThresholdPx) prevId?.let(onNavigate)
            },
            onHorizontalDrag = { _, dragAmount -> total += dragAmount },
        )
    }

    Column(
        Modifier.fillMaxSize().then(swipeModifier).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { prevId?.let(onNavigate) }, enabled = prevId != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous dive")
                }
                Text(
                    text = dive.number?.let { "Dive #$it" } ?: "Dive",
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = { nextId?.let(onNavigate) }, enabled = nextId != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next dive")
                }
            }
            androidx.compose.material3.TextButton(onClick = onEdit) { Text("Edit") }
        }

        if (samples.size >= 2) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                ProfileGraph(samples, events, unitSystem, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }

        // Compact computer switcher, only when a dive carries more than one computer.
        // A single small chip row selects which computer's profile is shown; the split
        // action targets whichever computer is selected.
        if (records.size > 1) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    records.forEachIndexed { i, rec ->
                        FilterChip(
                            selected = selectedRecord?.id == rec.id,
                            onClick = { selectedRecord = rec },
                            label = {
                                Text(recordLabel(devices[rec.deviceId], i, rec.id == dive.primaryComputerRecordId))
                            },
                        )
                    }
                }
                selectedRecord?.let { rec ->
                    androidx.compose.material3.TextButton(
                        onClick = { container.dives.splitRecordIntoNewDive(rec.id); onChanged() },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Split out") }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                SummaryRow("Date", Format.dateTime(dive.startEpochSeconds, dive.utcOffsetSeconds))
                SummaryRow("Duration", "${Format.duration(dive.durationSeconds)} min")
                SummaryRow("Max depth", Format.depth(dive.maxDepthMm, unitSystem))
                SummaryRow("Avg depth", Format.depth(dive.meanDepthMm, unitSystem))
                SummaryRow("Water temp", Format.temperature(dive.waterTempMk, unitSystem))
                // Source shows the selected computer and links to it in Settings so its
                // nickname can be edited. Imported-from-file records have no device to open.
                val sourceDeviceId = selectedRecord?.deviceId
                SummaryRow(
                    "Source",
                    deviceDescription(devices[sourceDeviceId]),
                    onClick = sourceDeviceId?.let { { onOpenDevice(it) } },
                )
                SummaryRow("Site", site?.name ?: "-")
                SummaryRow("Buddies", if (buddies.isEmpty()) "-" else buddies.joinToString { it.name })
                if (!dive.notes.isNullOrBlank()) {
                    SummaryRow("Notes", dive.notes!!)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.TextButton(onClick = { showMerge = true }) { Text("Merge in") }
            androidx.compose.material3.TextButton(
                onClick = { showDelete = true },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        }

        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 8.dp))
    }
}

private fun recordLabel(device: Device?, index: Int, isPrimary: Boolean): String {
    val base = if (device == null) "Computer ${index + 1}: ${deviceName(null)}" else deviceName(device)
    return if (isPrimary) "$base (primary)" else base
}

@Composable
private fun SummaryRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
