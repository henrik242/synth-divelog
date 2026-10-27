package no.synth.divelog.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.Backpack
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.components.EmptyState

/**
 * The gear inventory as a table, with as many columns as the width allows, searched,
 * filtered by kind and sorted by a tapped column header. Add and import sit in the top
 * bar ([onTopBarActions]) while it is shown. [onOpen] opens a piece, also a new one;
 * [onImport] reads a MacDive gear list, and is null where files cannot be picked.
 */
@Composable
internal fun GearListScreen(
    container: AppContainer,
    onOpen: (Long) -> Unit,
    onImport: (() -> Unit)?,
    onTopBarActions: ((@Composable RowScope.() -> Unit)?) -> Unit,
) {
    val open by rememberUpdatedState(onOpen)
    val import by rememberUpdatedState(onImport)
    val canImport = onImport != null
    val actions: @Composable RowScope.() -> Unit = remember(container, canImport) {
        {
            IconButton(onClick = { open(container.gear.add(GearItem(name = "New gear"))) }) {
                Icon(Icons.Filled.Add, contentDescription = "Add gear")
            }
            if (canImport) ImportMenu { import?.invoke() }
        }
    }
    DisposableEffect(actions) {
        onTopBarActions(actions)
        onDispose { onTopBarActions(null) }
    }
    val gear = observe(container, read = { container.gear.allWithLastService() }, flow = { container.gear.allWithLastServiceFlow() })
    val today = remember { today() }
    var query by rememberSaveable { mutableStateOf("") }
    // Saved as names: a set of enums does not survive state saving on every platform.
    var kindNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selectedKinds = kindNames.mapNotNull(GearKind::fromStored).toSet()
    var sortColumn by rememberSaveable { mutableStateOf(GearColumn.NAME) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var withRetired by rememberSaveable { mutableStateOf(false) }
    val anyRetired = gear.any { it.item.retired }
    val kinds = remember(gear, withRetired) {
        gear.filter { withRetired || !it.item.retired }.mapNotNull { it.item.kind }.distinct().sorted()
    }
    val shown = remember(gear, query, kindNames, withRetired, sortColumn, descending) {
        sortGear(filterGear(gear, query, selectedKinds, withRetired), sortColumn, descending)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 900.dp -> GearColumn.entries
            maxWidth >= 600.dp -> listOf(GearColumn.NAME, GearColumn.KIND, GearColumn.BRAND, GearColumn.SERVICED)
            else -> listOf(GearColumn.NAME, GearColumn.SERVICED)
        }
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            if (gear.isEmpty()) {
                EmptyState(
                    "No gear yet. Add your regulators, suits and the rest with +" +
                        if (canImport) ", or import a MacDive gear list from the import menu." else ".",
                    Icons.Outlined.Backpack,
                )
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.weight(1f))
                FilterMenu(
                    kinds = kinds,
                    selected = selectedKinds,
                    onSelected = { set -> kindNames = set.map { it.name } },
                    showRetiredOption = anyRetired,
                    withRetired = withRetired,
                    onWithRetired = { withRetired = it },
                )
            }
            HeaderRow(columns, sortColumn, descending) { column ->
                if (column == sortColumn) descending = !descending else { sortColumn = column; descending = false }
            }
            if (shown.isEmpty()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "No gear matches.",
                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { query = ""; kindNames = emptyList(); withRetired = false }) { Text("Clear search and filters") }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.item.id }) { (item, lastService) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        columns.forEach { column ->
                            Column(Modifier.weight(column.weight)) { Cell(column, item, lastService, today, wide) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The top bar's import menu. */
@Composable
private fun ImportMenu(onImportMacDive: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.FileUpload, contentDescription = "Import") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("MacDive gear list (CSV)") },
                onClick = { expanded = false; onImportMacDive() },
            )
        }
    }
}

/**
 * The filters beside the search field: retired gear on or off (when there is any) and the
 * [selected] of [kinds], all when none is. It stays open while ticking; the icon carries a
 * dot while a filter is on.
 */
@Composable
private fun FilterMenu(
    kinds: List<GearKind>,
    selected: Set<GearKind>,
    onSelected: (Set<GearKind>) -> Unit,
    showRetiredOption: Boolean,
    withRetired: Boolean,
    onWithRetired: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val check: @Composable (Boolean) -> Unit = { on -> if (on) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) }
    Box {
        IconButton(onClick = { expanded = true }) {
            BadgedBox(badge = { if (selected.isNotEmpty() || withRetired) Badge() }) {
                Icon(Icons.Filled.FilterList, contentDescription = "Filter")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showRetiredOption) {
                DropdownMenuItem(
                    text = { Text("Show retired") },
                    leadingIcon = { check(withRetired) },
                    onClick = { onWithRetired(!withRetired) },
                    modifier = checkbox(withRetired),
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text("All kinds") },
                leadingIcon = { check(selected.isEmpty()) },
                onClick = { onSelected(emptySet()) },
                modifier = checkbox(selected.isEmpty()),
            )
            kinds.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.label) },
                    leadingIcon = { check(k in selected) },
                    onClick = { onSelected(if (k in selected) selected - k else selected + k) },
                    modifier = checkbox(k in selected),
                )
            }
        }
    }
}

/** A menu item read out as a ticked or unticked checkbox. */
private fun checkbox(on: Boolean) = Modifier.semantics {
    role = Role.Checkbox
    toggleableState = ToggleableState(on)
}

/** Column titles; tapping one sorts by it, tapping it again turns the order around. */
@Composable
private fun HeaderRow(columns: List<GearColumn>, sortColumn: GearColumn, descending: Boolean, onSort: (GearColumn) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            columns.forEach { column ->
                val sorted = column == sortColumn
                Row(
                    Modifier.weight(column.weight).heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onSort(column) }
                        .semantics { if (sorted) stateDescription = if (descending) "Sorted descending" else "Sorted ascending" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        column.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (sorted) {
                        Icon(
                            if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One cell. Without the wide columns, the name cell also says what the gear is. */
@Composable
private fun Cell(column: GearColumn, item: GearItem, lastService: Int?, today: LocalDate, wide: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    when (column) {
        GearColumn.NAME -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (item.retired) "${item.name} (retired)" else item.name,
                    color = if (item.retired) muted else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.deviceId != null) {
                    Icon(Icons.Outlined.Watch, contentDescription = "Downloaded computer", Modifier.size(16.dp), tint = muted)
                }
            }
            if (!wide) {
                listOfNotNull(item.kind?.label, brandModel(item)).joinToString(" - ").ifBlank { null }?.let {
                    Text(it, style = small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        GearColumn.KIND -> Text(item.kind?.label ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
        GearColumn.BRAND -> Text(brandModel(item) ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
        GearColumn.SERIAL -> Text(item.serialNumber ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
        GearColumn.PURCHASED -> Text(item.purchasedEpochDay?.let { dateOf(it).toString() } ?: "")
        GearColumn.SERVICED -> if (lastService == null) {
            Text("Never", color = muted)
        } else {
            Text(shortTimeSince(dateOf(lastService), today), maxLines = 1)
        }
    }
}
