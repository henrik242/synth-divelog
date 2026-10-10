package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.components.EmptyState

/**
 * The Dives section: the searchable dive list, or the open dive [openDiveId] (in its
 * editor when [editing]). The list keeps its filters and scroll position while a dive is
 * open. The list follows the database.
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (dives.isEmpty()) {
                EmptyState(
                    if (canDownload) {
                        "No dives yet. Tap Add dives to download from a computer or import a file."
                    } else {
                        "No dives yet. Tap Add dives to import a file."
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
                            items(monthDives) { dive ->
                                DiveRow(dive, unitSystem, onClick = { onOpenDive(dive.id) }, siteName = dive.siteId?.let { siteNames[it] }, source = sources[dive.id])
                                HorizontalDivider()
                            }
                        }
                    } else {
                        items(shown) { dive ->
                            DiveRow(dive, unitSystem, onClick = { onOpenDive(dive.id) }, siteName = dive.siteId?.let { siteNames[it] }, source = sources[dive.id])
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAddDives,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add dives") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

private enum class DiveSort(val label: String) { DATE("Date"), NUMBER("Number"), DEPTH("Depth"), DURATION("Duration") }
