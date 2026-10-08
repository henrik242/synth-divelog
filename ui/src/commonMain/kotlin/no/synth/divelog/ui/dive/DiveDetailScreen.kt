package no.synth.divelog.ui.dive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Tank
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.core.model.units.Units
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.sites.SiteLocationMap
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiveDetailScreen(
    container: AppContainer,
    diveId: Long,
    unitSystem: UnitSystem,
    orderedDiveIds: List<Long> = emptyList(),
    onNavigate: (Long) -> Unit = {},
    onOpenDevice: (Long) -> Unit = {},
    onOpenSite: (Long) -> Unit = {},
    onEdit: () -> Unit = {},
    onDeleted: () -> Unit = {},
    onTopBarActions: ((@Composable RowScope.() -> Unit)?) -> Unit = {},
) {
    val dive = observe(diveId, read = { container.dives.getDive(diveId) }, flow = { container.dives.diveFlow(diveId) }) ?: run {
        Text("Dive not found", Modifier.padding(16.dp)); return
    }
    val records = observe(diveId, read = { container.dives.recordsForDive(diveId) }, flow = { container.dives.recordsForDiveFlow(diveId) })
    val devices = remember(records) {
        records.mapNotNull { it.deviceId }.distinct().associateWith { container.devices.get(it) }
    }
    val site = dive.siteId?.let { id -> observe(id, read = { container.sites.site(id) }, flow = { container.sites.siteFlow(id) }) }
    val buddies = observe(diveId, read = { container.buddies.buddiesForDive(diveId) }, flow = { container.buddies.buddiesForDiveFlow(diveId) })
    val tags = observe(diveId, read = { container.tags.tagsForDive(diveId) }, flow = { container.tags.tagsForDiveFlow(diveId) })
    val tanks = observe(diveId, read = { container.gases.tanksForDive(diveId) }, flow = { container.gases.tanksForDiveFlow(diveId) })
    val gases = remember(tanks, unitSystem) {
        tanks.mapNotNull { t -> tankLabel(t, t.gasMixId?.let { container.gases.gasMix(it) }, unitSystem) }
    }

    // Previous/next step through [orderedDiveIds] (index-1 / index+1).
    val index = orderedDiveIds.indexOf(diveId)
    val prevId = if (index > 0) orderedDiveIds[index - 1] else null
    val nextId = if (index >= 0 && index < orderedDiveIds.lastIndex) orderedDiveIds[index + 1] else null

    // Resolved against the current records, so a split-out record falls back to the primary.
    var selectedRecordId by remember(diveId) { mutableStateOf<Long?>(null) }
    val selectedRecord = records.firstOrNull { it.id == selectedRecordId }
        ?: records.firstOrNull { it.id == dive.primaryComputerRecordId }
        ?: records.firstOrNull()
    val samples = remember(selectedRecord?.id) {
        selectedRecord?.let { container.dives.samplesForRecord(it.id) } ?: emptyList()
    }
    val events = remember(selectedRecord?.id) {
        selectedRecord?.let { container.dives.eventsForRecord(it.id) } ?: emptyList()
    }

    var showMerge by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete dive?") },
            text = { Text("This removes the dive and its computer records.") },
            confirmButton = {
                TextButton(
                    onClick = { container.dives.deleteDive(diveId); showDelete = false; onDeleted() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }

    if (showMerge) {
        val others = remember { container.dives.allDives().filter { it.id != diveId } }
        AlertDialog(
            onDismissRequest = { showMerge = false },
            title = { Text("Merge another dive into this one") },
            text = {
                if (others.isEmpty()) {
                    Text("No other dives to merge.")
                } else {
                    LazyColumn {
                        items(others) { other ->
                            Text(
                                text = (other.number?.let { "#$it  " } ?: "") +
                                    Format.dateTime(other.startEpochSeconds, other.utcOffsetSeconds),
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        container.dives.mergeDives(sourceDiveId = other.id, targetDiveId = diveId)
                                        showMerge = false
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMerge = false }) { Text("Cancel") } },
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

    // Left/right arrow keys (desktop, hardware keyboards) do the same as the arrow buttons.
    val focus = remember { FocusRequester() }
    LaunchedEffect(diveId) { focus.requestFocus() }
    val keyModifier = Modifier.focusRequester(focus).focusable().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val target = when (event.key) {
            Key.DirectionLeft -> prevId
            Key.DirectionRight -> nextId
            else -> null
        }
        target?.let(onNavigate)
        target != null
    }

    // Previous/next and the actions menu sit in the top bar, beside the breadcrumb that
    // already names the dive.
    val topBarActions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { prevId?.let(onNavigate) }, enabled = prevId != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous dive")
        }
        IconButton(onClick = { nextId?.let(onNavigate) }, enabled = nextId != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next dive")
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Dive actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text("Merge in") }, onClick = { menuOpen = false; showMerge = true })
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; showDelete = true },
                )
            }
        }
    }
    SideEffect { onTopBarActions(topBarActions) }
    DisposableEffect(Unit) { onDispose { onTopBarActions(null) } }

    Column(
        Modifier.fillMaxSize().then(keyModifier).then(swipeModifier).verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

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
                            onClick = { selectedRecordId = rec.id },
                            label = {
                                Text(recordLabel(devices[rec.deviceId], i, rec.id == dive.primaryComputerRecordId))
                            },
                        )
                    }
                }
                selectedRecord?.let { rec ->
                    TextButton(
                        onClick = { container.dives.splitRecordIntoNewDive(rec.id) },
                        colors = ButtonDefaults.textButtonColors(
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
                SummaryRow("Gases", if (gases.isEmpty()) "-" else gases.joinToString("\n"))
                // Source shows the selected computer and links to it in Settings so its
                // nickname can be edited. Imported-from-file records have no device to open.
                val sourceDeviceId = selectedRecord?.deviceId
                SummaryRow(
                    "Source",
                    deviceDescription(devices[sourceDeviceId]),
                    onClick = sourceDeviceId?.let { { onOpenDevice(it) } },
                )
                SummaryRow(
                    "Site",
                    site?.name ?: "-",
                    onClick = dive.siteId?.let { id -> { onOpenSite(id) } },
                )
                SummaryRow("Buddies", if (buddies.isEmpty()) "-" else buddies.joinToString { it.name })
                SummaryRow("Tags", if (tags.isEmpty()) "-" else tags.joinToString { it.name })
                // Notes can be long and multi-line, so lay them out full-width and
                // left-aligned under the label instead of in the right-aligned value column.
                dive.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            "Notes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        // A small map of the dive's site, when it has a coordinate.
        site?.let { s ->
            if (s.latitude != null && s.longitude != null) {
                SiteLocationMap(
                    latitude = s.latitude,
                    longitude = s.longitude,
                    interactive = false,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            }
        }

        Spacer(Modifier.padding(bottom = 8.dp))
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

/**
 * "EAN32 · 12 L · 210 → 60 bar", with only what the tank records (0 counts as not
 * recorded); null when it records nothing.
 */
private fun tankLabel(tank: Tank, gas: GasMix?, system: UnitSystem): String? {
    val start = tank.startPressureMbar?.takeIf { it > 0 }
    val end = tank.endPressureMbar?.takeIf { it > 0 }
    val pressures = when {
        start != null && end != null ->
            "${Units.pressure(start, system).value.roundToInt()} → ${Format.pressure(end, system)}"
        else -> (start ?: end)?.let { Format.pressure(it, system) }
    }
    return listOfNotNull(
        gas?.takeIf { it.o2Permille > 0 }?.let { Format.gasName(it.o2Permille, it.hePermille) },
        tank.volumeMl?.takeIf { it > 0 }?.let { Format.tankSize(it, tank.workingPressureMbar, system) },
        pressures,
    ).joinToString(" · ").ifEmpty { null }
}
