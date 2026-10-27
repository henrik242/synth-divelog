package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Pickers offer a search field above this many options. */
private const val SEARCH_FROM = 8

/**
 * A chip per facet under the search field, each opening its picker, and Clear all while a
 * filter is on. The location ([locations]) comes after the year. [wide] wraps the chips and
 * opens pickers under them; otherwise the chips scroll sideways and pickers open as a bottom sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FacetChipRow(
    facets: List<Facet>,
    locations: List<LocationNode>,
    filter: DiveFilter,
    onFilter: (DiveFilter) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<String?>(null) }
    val chips = buildList<Pair<String, @Composable () -> Unit>> {
        fun flat(facet: Facet) = add(facet.kind.name to @Composable {
            val picked = facet.options.filter { it.id in facet.picked }
            PickerChip(
                label = when (picked.size) {
                    0 -> facet.kind.noun
                    1 -> picked.single().name
                    else -> "${facet.kind.noun} · ${picked.size}"
                },
                noun = facet.kind.noun,
                pickedCount = picked.size,
                wide = wide,
                isOpen = open == facet.kind.name,
                onOpen = { open = facet.kind.name },
                onClose = { open = null },
            ) { focusSearch -> FacetPicker(facet, { onFilter(filter.with(facet.kind, it)) }, focusSearch) }
        })
        facets.filter { it.kind == FacetKind.YEAR }.forEach(::flat)
        if (locations.isNotEmpty()) {
            add("LOCATION" to @Composable {
                val count = filter.locationCount
                PickerChip(
                    label = when (count) {
                        0 -> "Location"
                        1 -> locations.flatMap { listOf(it) + it.descendants() }.firstOrNull { it.id in filter[it.kind] }?.name ?: "Location"
                        else -> "Location · $count"
                    },
                    noun = "Location",
                    pickedCount = count,
                    wide = wide,
                    isOpen = open == "LOCATION",
                    onOpen = { open = "LOCATION" },
                    onClose = { open = null },
                ) { focusSearch -> LocationPicker(locations, filter, onFilter, focusSearch) }
            })
        }
        facets.filter { it.kind != FacetKind.YEAR }.forEach(::flat)
    }

    @Composable
    fun ClearAll() {
        if (!filter.isEmpty) AssistChip(onClick = { onFilter(DiveFilter()) }, label = { Text("Clear all") })
    }

    if (wide) {
        FlowRow(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { it.second() }
            ClearAll()
        }
    } else {
        LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips, key = { it.first }) { it.second() }
            item { ClearAll() }
        }
    }
}

/**
 * A dropdown chip, and while [isOpen] its [picker]: under the chip when [wide], else in a
 * bottom sheet. The picker gets whether to focus its search field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerChip(
    label: String,
    noun: String,
    pickedCount: Int,
    wide: Boolean,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    picker: @Composable ColumnScope.(focusSearch: Boolean) -> Unit,
) {
    Box {
        FilterChip(
            selected = pickedCount > 0,
            onClick = onOpen,
            label = { Text(label, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.semantics {
                role = Role.DropdownList
                stateDescription = if (pickedCount == 0) "Any ${noun.lowercase()}" else "$pickedCount selected"
            },
        )
        if (isOpen && wide) {
            Popup(popupPositionProvider = BelowAnchor, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
                Surface(
                    Modifier.width(340.dp).heightIn(max = 460.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(vertical = 8.dp).closeOnEscape(onClose)) { picker(true) }
                }
            }
        }
    }
    if (isOpen && !wide) {
        ModalBottomSheet(onDismissRequest = onClose) { picker(false) }
    }
}

private fun Modifier.closeOnEscape(onClose: () -> Unit) = onPreviewKeyEvent {
    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onClose(); true } else false
}

/** The picker's title, and Clear for its picks. */
@Composable
private fun PickerHeader(title: String, canClear: Boolean, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear, enabled = canClear) { Text("Clear") }
    }
}

/** The search field of a picker; Enter runs [onEnter]. */
@Composable
private fun PickerSearch(query: String, onQuery: (String) -> Unit, placeholder: String, focus: Boolean, onEnter: () -> Unit) {
    val requester = remember { FocusRequester() }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEnter() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(requester),
    )
    if (focus) LaunchedEffect(Unit) { requester.requestFocus() }
}

@Composable
private fun NoMatches(what: String, typed: String) {
    Text("No $what match “$typed”", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A row's name, dimmed when it would give no dives, and its dive count. */
@Composable
private fun RowScope.NameAndCount(name: String, count: Int, on: Boolean) {
    Text(
        name,
        Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (count == 0 && !on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        "$count",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp),
    )
}

/**
 * A facet's options to tick, each with its dive count. A search field narrows long lists;
 * Enter in it ticks the top match, and Select all ticks every match. What was picked when it
 * opened stays on top, so rows do not jump while ticking.
 */
@Composable
private fun ColumnScope.FacetPicker(facet: Facet, onPick: (Set<Long>) -> Unit, focusSearch: Boolean) {
    var query by remember { mutableStateOf("") }
    val pickedAtOpen = remember { facet.picked }
    val typed = query.trim()
    val shown = facet.options
        .filter { typed.isEmpty() || it.name.contains(typed, ignoreCase = true) }
        .sortedByDescending { it.id in pickedAtOpen }
    fun toggle(id: Long) = onPick(if (id in facet.picked) facet.picked - id else facet.picked + id)

    PickerHeader(facet.kind.title, facet.picked.isNotEmpty()) { onPick(emptySet()) }
    if (facet.options.size > SEARCH_FROM) {
        PickerSearch(query, { query = it }, "Find a ${facet.kind.noun.lowercase()}", focusSearch) {
            shown.firstOrNull()?.let { toggle(it.id) }
        }
    }
    if (shown.isEmpty()) NoMatches(facet.kind.title.lowercase(), typed)
    if (typed.isNotEmpty() && shown.size > 1) {
        val ids = shown.map { it.id }.toSet()
        val all = facet.picked.containsAll(ids)
        TextButton(
            onClick = { onPick(if (all) facet.picked - ids else facet.picked + ids) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(if (all) "Clear ${ids.size} shown" else "Select all ${ids.size} shown") }
    }
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(shown, key = { it.id }) { option ->
            val on = option.id in facet.picked
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = { toggle(option.id) })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                NameAndCount(option.name, option.count, on)
            }
        }
    }
}

/** A row of the location tree: the node with the nodes above it, and whether it is open. */
private data class TreeRow(val path: List<LocationNode>, val open: Boolean)

private fun key(node: LocationNode) = "${node.kind.name}:${node.id}"

/**
 * The visible rows of [nodes]. While [typed] is set, a node shows when it, a node above it or
 * one below it matches, and nodes with a match below them open.
 */
private fun treeRows(nodes: List<LocationNode>, typed: String, opened: Set<String>): List<TreeRow> {
    fun hit(node: LocationNode) = node.name.contains(typed, ignoreCase = true)
    fun hitBelow(node: LocationNode): Boolean = node.children.any { hit(it) || hitBelow(it) }
    val rows = mutableListOf<TreeRow>()
    fun walk(level: List<LocationNode>, above: List<LocationNode>, aboveHit: Boolean) {
        for (node in level) {
            val path = above + node
            val below = typed.isNotEmpty() && hitBelow(node)
            val self = typed.isEmpty() || aboveHit || hit(node)
            if (!self && !below) continue
            val open = key(node) in opened || below
            rows += TreeRow(path, open)
            if (open) walk(node.children, path, typed.isEmpty() || aboveHit || hit(node))
        }
    }
    walk(nodes, emptyList(), false)
    return rows
}

/**
 * Countries, their places and those places' sites to tick, each with its dive count. Ticking
 * a country or place takes in everything in it, also what is added later. Nodes with picks
 * below them start open.
 */
@Composable
private fun ColumnScope.LocationPicker(
    locations: List<LocationNode>,
    filter: DiveFilter,
    onFilter: (DiveFilter) -> Unit,
    focusSearch: Boolean,
) {
    var query by remember { mutableStateOf("") }
    var opened by remember {
        mutableStateOf(locations.flatMap { listOf(it) + it.descendants() }.filter { n -> n.descendants().any { it.id in filter[it.kind] } }.map(::key).toSet())
    }
    val typed = query.trim()
    val rows = treeRows(locations, typed, opened)
    val total = locations.sumOf { 1 + it.descendants().size }

    PickerHeader("Locations", filter.locationCount > 0) {
        onFilter(filter.with(FacetKind.COUNTRY, emptySet()).with(FacetKind.PLACE, emptySet()).with(FacetKind.SITE, emptySet()))
    }
    if (total > SEARCH_FROM) {
        PickerSearch(query, { query = it }, "Find a country, place or site", focusSearch) {
            rows.firstOrNull { it.path.last().name.contains(typed, ignoreCase = true) }?.let { onFilter(filter.toggleLocation(it.path)) }
        }
    }
    if (rows.isEmpty()) NoMatches("locations", typed)
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(rows, key = { row -> row.path.joinToString("/") { key(it) } }) { row ->
            val node = row.path.last()
            val state = filter.locationState(row.path)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .triStateToggleable(state = state, role = Role.Checkbox, onClick = { onFilter(filter.toggleLocation(row.path)) })
                    .padding(start = 4.dp + 20.dp * (row.path.size - 1), end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (node.children.isNotEmpty()) {
                    IconButton(onClick = { opened = if (row.open) opened - key(node) else opened + key(node) }) {
                        Icon(
                            if (row.open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (row.open) "Close ${node.name}" else "Open ${node.name}",
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                TriStateCheckbox(state = state, onClick = null)
                Spacer(Modifier.width(12.dp))
                NameAndCount(node.name, node.count, state == ToggleableState.On)
            }
        }
    }
}

/** Under the anchor, its left edge kept on screen; above it when there is no room below. */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
