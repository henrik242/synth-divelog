package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.common.BackHandler
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.components.ExportActions
import no.synth.divelog.ui.components.ExportDialog

/**
 * The Dives section: the searchable dive list, or the open dive [openDiveId] (in its
 * editor when [editing]). The list keeps its filters and scroll position while a dive is
 * open. The list follows the database. Select (in the top bar) or a long press picks dives
 * to export through [exportActions], merge into one or delete.
 */
@Composable
fun DivesScreen(
    container: AppContainer,
    unitSystem: UnitSystem,
    openDiveId: Long?,
    editing: Boolean,
    onOpenDive: (Long) -> Unit,
    onNavigate: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenDevice: (Long) -> Unit,
    onOpenSite: (Long) -> Unit,
    onDeleted: () -> Unit,
    onAddDives: () -> Unit,
    canDownload: Boolean,
    exportActions: ExportActions,
    onTopBarActions: ((@Composable RowScope.() -> Unit)?) -> Unit,
) {
    val dives = observe(container, read = container.dives::allDives, flow = container.dives::allDivesFlow)
    val sites = observe(container, read = container.sites::allSites, flow = container.sites::allSitesFlow)
    val siteNames = remember(sites) { sites.associate { it.id to it.name } }
    val sourcesByDive = observe(container, read = container.dives::sourcesByDive, flow = container.dives::sourcesByDiveFlow)
    val sources = remember(sourcesByDive) { sourceLabels(sourcesByDive) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(DiveSort.DATE) }
    var selectedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(stateSaver = DiveSelection.Saver) { mutableStateOf(DiveSelection()) }
    var dialog by rememberSaveable { mutableStateOf<SelectionDialog?>(null) }

    val years = remember(dives) {
        dives.map { Format.year(it.startEpochSeconds, it.utcOffsetSeconds) }.distinct().sortedDescending()
    }

    val shown = remember(dives, query, sort, selectedYear, siteNames) {
        val q = query.trim().lowercase()
        dives
            .filter { selectedYear == null || Format.year(it.startEpochSeconds, it.utcOffsetSeconds) == selectedYear }
            .filter { d ->
                if (q.isEmpty()) true
                else {
                    val site = d.siteId?.let { siteNames[it] } ?: ""
                    d.number?.toString()?.contains(q) == true ||
                        site.lowercase().contains(q) ||
                        Format.date(d.startEpochSeconds, d.utcOffsetSeconds).lowercase().contains(q) ||
                        (d.notes?.lowercase()?.contains(q) == true)
                }
            }
            .sortedWith(
                when (sort) {
                    DiveSort.DATE -> compareByDescending { it.startEpochSeconds }
                    DiveSort.NUMBER -> compareByDescending { it.number ?: 0 }
                    DiveSort.DEPTH -> compareByDescending { it.maxDepthMm ?: 0 }
                    DiveSort.DURATION -> compareByDescending { it.durationSeconds }
                },
            )
    }
    // Group by month only for the date sort, where month headers make sense.
    val grouped = remember(shown, sort) {
        if (sort == DiveSort.DATE) {
            shown.groupBy { Format.monthYear(it.startEpochSeconds, it.utcOffsetSeconds) }
        } else {
            null
        }
    }

    if (openDiveId != null) {
        if (editing) {
            DiveEditScreen(container = container, diveId = openDiveId, unitSystem = unitSystem, onCancel = onClose)
        } else {
            DiveDetailScreen(
                container = container,
                diveId = openDiveId,
                unitSystem = unitSystem,
                // Prev/next walk the filtered list behind this screen. It is sorted descending
                // (newest, deepest, ... first), so reverse it: right goes newer, left older.
                orderedDiveIds = shown.map { it.id }.asReversed(),
                onNavigate = onNavigate,
                onOpenDevice = onOpenDevice,
                onOpenSite = onOpenSite,
                onEdit = { onEdit(openDiveId) },
                onDeleted = onDeleted,
                onTopBarActions = onTopBarActions,
            )
        }
        return
    }

    val diveIds = remember(dives) { dives.mapTo(HashSet()) { it.id } }
    val shownIds = remember(shown) { shown.map { it.id } }
    // Dives deleted elsewhere drop out of the selection.
    LaunchedEffect(diveIds) { selection = selection.retainExisting(diveIds) }
    // The dialogs go with the selection, so they cannot come back on their own later.
    LaunchedEffect(selecting, selection.isEmpty) {
        if (!selecting || selection.isEmpty) dialog = null
    }

    fun endSelecting() {
        selecting = false
        selection = DiveSelection()
    }
    BackHandler(enabled = selecting) { endSelecting() }

    // Select in the top bar while the list shows and nothing is being picked.
    val selectAction: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Checklist, contentDescription = "Select dives") }
    }
    DisposableEffect(selecting, dives.isEmpty()) {
        onTopBarActions(if (selecting || dives.isEmpty()) null else selectAction)
        onDispose { onTopBarActions(null) }
    }

    val onOpen: (Long) -> Unit = { id -> if (selecting) selection = selection.toggle(id) else onOpenDive(id) }
    val onPick: (Long, Boolean) -> Unit = { id, range ->
        selecting = true
        selection = if (range) selection.extendTo(id, shownIds) else selection.toggle(id)
    }

    when (dialog) {
        SelectionDialog.EXPORT -> ExportDialog(
            title = "Export ${Format.dives(selection.ids.size)}",
            actions = exportActions,
            diveIds = selection.ids,
            onExported = ::endSelecting,
            onDismiss = { dialog = null },
        )
        SelectionDialog.MERGE -> MergeDivesDialog(container, selection.ids, onMerged = ::endSelecting, onDismiss = { dialog = null })
        SelectionDialog.DELETE -> DeleteDivesDialog(
            count = selection.ids.size,
            hidden = selection.hiddenCount(shownIds),
            onDelete = {
                container.dives.deleteDives(selection.ids)
                endSelecting()
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionBar(
                    count = selection.ids.size,
                    hidden = selection.hiddenCount(shownIds),
                    allShownPicked = selection.allPicked(shownIds),
                    onClose = ::endSelecting,
                    onToggleAll = { selection = selection.toggleAll(shownIds) },
                    onExport = if (exportActions.available) ({ dialog = SelectionDialog.EXPORT }) else null,
                    onMerge = { dialog = SelectionDialog.MERGE },
                    onDelete = { dialog = SelectionDialog.DELETE },
                )
            }
            if (dives.isEmpty()) {
                EmptyState(
                    if (canDownload) {
                        "No dives yet. Tap Add dives to download from a computer, import a file or enter a dive by hand."
                    } else {
                        "No dives yet. Tap Add dives to import a file or enter a dive by hand."
                    },
                    Icons.Outlined.Waves,
                )
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(DiveSort.entries.toList()) { s ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
                    }
                }
                if (years.size > 1) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedYear == null,
                                onClick = { selectedYear = null },
                                label = { Text("All") },
                            )
                        }
                        items(years) { y ->
                            FilterChip(
                                selected = selectedYear == y,
                                onClick = { selectedYear = if (selectedYear == y) null else y },
                                label = { Text("$y") },
                            )
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                    if (grouped != null) {
                        grouped.forEach { (month, monthDives) ->
                            item {
                                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(
                                        month,
                                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(monthDives, key = { it.id }) { dive ->
                                DiveItem(dive, unitSystem, siteNames, sources, selecting, selection, onOpen, onPick)
                                HorizontalDivider()
                            }
                        }
                    } else {
                        items(shown, key = { it.id }) { dive ->
                            DiveItem(dive, unitSystem, siteNames, sources, selecting, selection, onOpen, onPick)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (!selecting) {
            ExtendedFloatingActionButton(
                onClick = onAddDives,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add dives") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

/** A [DiveRow] in the list: [onOpen] opens or (while [selecting]) toggles it; [onPick] picks it. */
@Composable
private fun DiveItem(
    dive: Dive,
    unitSystem: UnitSystem,
    siteNames: Map<Long, String>,
    sources: Map<Long, String>,
    selecting: Boolean,
    selection: DiveSelection,
    onOpen: (Long) -> Unit,
    onPick: (Long, Boolean) -> Unit,
) {
    DiveRow(
        dive,
        unitSystem,
        onClick = { onOpen(dive.id) },
        siteName = dive.siteId?.let { siteNames[it] },
        source = sources[dive.id],
        selected = if (selecting) dive.id in selection else null,
        onPick = { range -> onPick(dive.id, range) },
    )
}

/**
 * Shown while picking dives: the count ([hidden] of them filtered out of the list), close,
 * select or deselect all shown, export (when [onExport] is set), merge and delete.
 */
@Composable
private fun SelectionBar(
    count: Int,
    hidden: Int,
    allShownPicked: Boolean,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
    onExport: (() -> Unit)?,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Stop selecting") }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text("$count selected", style = MaterialTheme.typography.titleMedium)
                if (hidden > 0) {
                    Text("$hidden hidden by the filter", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onToggleAll) { Text(if (allShownPicked) "Deselect all" else "Select all") }
            if (onExport != null) TextButton(onClick = onExport, enabled = count > 0) { Text("Export") }
            IconButton(onClick = onMerge, enabled = count > 1) { Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = "Merge") }
            IconButton(onClick = onDelete, enabled = count > 0) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

private enum class SelectionDialog { EXPORT, MERGE, DELETE }

/** Asks before merging [ids] into one dive, or says why they cannot be. */
@Composable
private fun MergeDivesDialog(container: AppContainer, ids: Set<Long>, onMerged: () -> Unit, onDismiss: () -> Unit) {
    val problem = remember(ids) { container.dives.mergeProblem(ids) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge ${Format.dives(ids.size)}?") },
        text = { Text(problem?.let(::mergeProblemText) ?: MERGE_EXPLAINED) },
        confirmButton = {
            if (problem == null) {
                TextButton(onClick = {
                    container.dives.mergeDives(ids)
                    onMerged()
                }) { Text("Merge") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks before deleting [count] dives, saying how many of them the filter hides. */
@Composable
private fun DeleteDivesDialog(count: Int, hidden: Int, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${Format.dives(count)}?") },
        text = {
            Text(
                listOfNotNull(
                    if (hidden > 0) "$hidden of them are hidden by the filter." else null,
                    if (count == 1) "This removes the dive and its computer records." else "This removes them and their computer records.",
                    "A downloaded dive comes back only if you download all or the latest dives again and check it in the review.",
                ).joinToString(" "),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class DiveSort(val label: String) { DATE("Date"), NUMBER("Number"), DEPTH("Depth"), DURATION("Duration") }
