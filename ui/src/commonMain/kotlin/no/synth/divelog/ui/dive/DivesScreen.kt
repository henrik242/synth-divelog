package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    onOpenGear: (Long) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    filter: DiveFilter,
    onFilter: (DiveFilter) -> Unit,
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
    var sort by rememberSaveable { mutableStateOf(DiveSort.DATE) }
    val listState = rememberLazyListState()
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(stateSaver = DiveSelection.Saver) { mutableStateOf(DiveSelection()) }
    var dialog by rememberSaveable { mutableStateOf<SelectionDialog?>(null) }

    val buddies = observe(container, read = container.buddies::all, flow = container.buddies::allFlow)
    val tags = observe(container, read = container.tags::all, flow = container.tags::allFlow)
    val gear = observe(container, read = container.gear::all, flow = container.gear::allFlow)
    val places = observe(container, read = container.sites::allPlaces, flow = container.sites::allPlacesFlow)
    val countries = observe(container, read = container.sites::countries, flow = container.sites::countriesFlow)
    val countryNames = remember(countries) { countries.associate { it.id to it.name } }
    // "Place, Country" per site, so the search finds dives by place and country too.
    val siteDetails = remember(sites, places, countryNames) {
        sites.associate { site ->
            val place = places[site.placeId]
            site.id to listOfNotNull(place?.name, place?.let { countryNames[it.countryId] }).joinToString(", ")
        }
    }
    val buddyLinks = observe(container, read = container.buddies::buddyIdsByDive, flow = container.buddies::buddyIdsByDiveFlow)
    val tagLinks = observe(container, read = container.tags::tagIdsByDive, flow = container.tags::tagIdsByDiveFlow)
    val gearLinks = observe(container, read = container.gear::gearIdsByDive, flow = container.gear::gearIdsByDiveFlow)
    val years = remember(dives) { dives.associate { it.id to yearOf(it) } }
    val links = remember(buddyLinks, tagLinks, gearLinks, sites, places, years) {
        DiveLinks(
            buddies = buddyLinks,
            tags = tagLinks,
            gear = gearLinks,
            sitePlaces = sites.associate { it.id to it.placeId },
            placeCountries = places.mapValues { it.value.countryId },
            years = years,
        )
    }
    // What the search looks in, per dive, worked out once rather than on every keystroke.
    val searchText = remember(dives, siteNames, siteDetails) {
        dives.associate { d ->
            d.id to listOfNotNull(
                d.number?.toString(),
                d.siteId?.let { siteNames[it] },
                d.siteId?.let { siteDetails[it] },
                Format.date(d.startEpochSeconds, d.utcOffsetSeconds),
                d.notes,
            ).joinToString("\n").lowercase()
        }
    }
    // The search narrows first; the facets count within what it leaves.
    val searched = remember(dives, query, searchText) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) dives else dives.filter { searchText[it.id]?.contains(q) == true }
    }
    val names = remember(countryNames, places, siteNames, buddies, tags, gear) {
        mapOf(
            FacetKind.COUNTRY to countryNames,
            FacetKind.PLACE to places.mapValues { it.value.name },
            FacetKind.SITE to siteNames,
            FacetKind.BUDDY to buddies.associate { it.id to it.name },
            FacetKind.TAG to tags.associate { it.id to it.name },
            FacetKind.GEAR to gear.associate { it.id to it.name },
        )
    }
    // Counting runs over every dive per kind, so off the main thread; the first frame counts in place.
    fun countFacets() = filter.facets(searched, links, names) to filter.locations(dives, searched, links, names)
    val (facets, locations) = produceState(remember { countFacets() }, filter, dives, searched, links, names) {
        value = withContext(Dispatchers.Default) { countFacets() }
    }.value

    val shown = remember(searched, sort, filter, links) {
        searched
            .filter { filter.matches(it, links) }
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
                onOpenGear = onOpenGear,
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
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    SortButton(sort) { sort = it }
                }
                // Pickers drop down under their chip where there is room for it, also in height.
                val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    FacetChipRow(facets, locations, filter, onFilter, shown.size, wide = maxWidth >= 600.dp && windowHeight >= 480.dp)
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

/** The sort beside the search field, with a dot when it is not by date. */
@Composable
private fun SortButton(sort: DiveSort, onSort: (DiveSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            BadgedBox(badge = { if (sort != DiveSort.DATE) Badge() }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort: ${sort.label}")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DiveSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    leadingIcon = { if (s == sort) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { expanded = false; onSort(s) },
                    modifier = Modifier.semantics { role = Role.RadioButton; selected = s == sort },
                )
            }
        }
    }
}

private enum class DiveSort(val label: String) { DATE("Date"), NUMBER("Number"), DEPTH("Depth"), DURATION("Duration") }
