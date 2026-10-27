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
import androidx.compose.material3.Button
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FacetChipRow(
    counted: List<Facet>,
    locations: List<LocationNode>,
    filter: DiveFilter,
    onFilter: (DiveFilter) -> Unit,
    shownCount: Int,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    // The counts may lag the filter while they are worked out; the ticks must not.
    val facets = counted.map { it.copy(picked = filter[it.kind]) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { open = null }
    val picking = rememberSaveable(open, saver = PickerState.Saver) {
        PickerState(
            pickedAtOpen = facets.firstOrNull { it.kind.name == open }?.picked.orEmpty(),
            opened = if (open == LOCATION) locations.allNodes().filter { n -> n.descendants().any { it.id in filter[it.kind] } }.map(::key).toSet() else emptySet(),
        )
    }
    // Only with a keyboard in use; a touch screen would raise its own over the list.
    val focusSearch = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val content: (@Composable ColumnScope.() -> Unit)? = when (val key = open) {
        null -> null
        LOCATION -> locations.takeIf { it.isNotEmpty() }?.let { { LocationPicker(it, filter, onFilter, picking, focusSearch) } }
        else -> facets.firstOrNull { it.kind.name == key }?.let { facet ->
            { FacetPicker(facet, { onFilter(filter.with(facet.kind, it)) }, picking, focusSearch) }
        }
    }
    // Under the list, what the picks come to, and the way back to the dives.
    val picker: (@Composable ColumnScope.() -> Unit)? = content?.let { inner ->
        {
            inner()
            Button(onClick = close, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(if (shownCount == 1) "Show 1 dive" else "Show $shownCount dives")
            }
        }
    }
    fun popupFor(key: String) = picker.takeIf { wide && open == key }

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
                onOpen = { open = facet.kind.name },
                popup = popupFor(facet.kind.name),
                onClose = close,
            )
        })
        facets.filter { it.kind == FacetKind.YEAR }.forEach(::flat)
        if (locations.isNotEmpty()) {
            add(LOCATION to @Composable {
                val count = filter.locationCount
                PickerChip(
                    label = locationLabel(locations, filter),
                    noun = "Location",
                    pickedCount = count,
                    onOpen = { open = LOCATION },
                    popup = popupFor(LOCATION),
                    onClose = close,
                )
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
        // Outside the row, so the sheet stays while its chip scrolls away.
        if (picker != null) {
            ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { picker() }
        }
    }
}

private const val LOCATION = "LOCATION"

/**
 * What an open picker shows beyond the filter: the search, the location tree's open nodes, and
 * what was picked when it opened. The chip row holds it, so it outlasts the picker moving
 * between sheet and popup when the window turns.
 */
private class PickerState(val pickedAtOpen: Set<Long>, opened: Set<String>, query: String = "") {
    var query by mutableStateOf(query)
    var opened by mutableStateOf(opened)

    companion object {
        val Saver: Saver<PickerState, Any> = listSaver(
            save = { listOf(it.query, ArrayList(it.opened), it.pickedAtOpen.toLongArray()) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                PickerState(
                    pickedAtOpen = (saved[2] as LongArray).toSet(),
                    opened = (saved[1] as List<String>).toSet(),
                    query = saved[0] as String,
                )
            },
        )
    }
}

/**
 * "Location" with nothing picked, the one picked, "Gulen · 9 of 10" when the picks are all in
 * one place or country (as unticking one of its sites leaves them), else "Location · 3".
 */
internal fun locationLabel(locations: List<LocationNode>, filter: DiveFilter): String {
    val nodes = locations.allNodes()
    val picked = nodes.filter { it.id in filter[it.kind] }
    return when (picked.size) {
        0 -> "Location"
        1 -> picked.single().name
        else -> nodes.firstOrNull { n -> n.children.isNotEmpty() && n.children.containsAll(picked) }
            ?.let { "${it.name} · ${picked.size} of ${it.children.size}" }
            ?: "Location · ${picked.size}"
    }
}

/** A dropdown chip, and while [popup] is set that picker under it, no taller than the room below. */
@Composable
private fun PickerChip(
    label: String,
    noun: String,
    pickedCount: Int,
    onOpen: () -> Unit,
    popup: (@Composable ColumnScope.() -> Unit)?,
    onClose: () -> Unit,
) {
    var bottom by remember { mutableStateOf(0f) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    Box(Modifier.onGloballyPositioned { bottom = it.boundsInWindow().bottom }) {
        FilterChip(
            selected = pickedCount > 0,
            onClick = onOpen,
            label = { Text(label, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.semantics {
                role = Role.DropdownList
                // The chip says it is selected; this says how much.
                stateDescription = if (pickedCount == 0) "Any ${noun.lowercase()}" else "$pickedCount picked"
            },
        )
        if (popup != null) {
            val room = with(density) { (windowHeight - bottom).toDp() } - 16.dp
            Popup(popupPositionProvider = BelowAnchor, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
                Surface(
                    Modifier.width(340.dp).heightIn(max = room.coerceIn(200.dp, 460.dp)),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(vertical = 8.dp).closeOnEscape(onClose)) { popup() }
                }
            }
        }
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
        modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = if (count == 1) "1 dive" else "$count dives" },
    )
}

/**
 * A facet's options to tick, each with its dive count. A search field narrows long lists;
 * Enter in it ticks the top match, and Select all ticks every match. What was picked when it
 * opened stays on top, so rows do not jump while ticking.
 */
@Composable
private fun ColumnScope.FacetPicker(facet: Facet, onPick: (Set<Long>) -> Unit, picking: PickerState, focusSearch: Boolean) {
    val typed = picking.query.trim()
    val shown = facet.options
        .filter { typed.isEmpty() || it.name.contains(typed, ignoreCase = true) }
        .sortedByDescending { it.id in picking.pickedAtOpen }
    fun toggle(id: Long) = onPick(if (id in facet.picked) facet.picked - id else facet.picked + id)

    PickerHeader(facet.kind.title, facet.picked.isNotEmpty()) { onPick(emptySet()) }
    if (facet.options.size > SEARCH_FROM) {
        PickerSearch(picking.query, { picking.query = it }, "Find a ${facet.kind.noun.lowercase()}", focusSearch) {
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
    picking: PickerState,
    focusSearch: Boolean,
) {
    val typed = picking.query.trim()
    val rows = treeRows(locations, typed, picking.opened)
    val total = locations.allNodes().size

    PickerHeader("Locations", filter.locationCount > 0) {
        onFilter(filter.withoutLocation())
    }
    if (total > SEARCH_FROM) {
        PickerSearch(picking.query, { picking.query = it }, "Find a country, place or site", focusSearch) {
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
                    .semantics {
                        stateDescription = when (state) {
                            ToggleableState.On -> "Picked"
                            ToggleableState.Indeterminate -> "Partly picked"
                            ToggleableState.Off -> "Not picked"
                        }
                    }
                    .padding(start = 4.dp + 20.dp * (row.path.size - 1), end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (node.children.isNotEmpty()) {
                    IconButton(onClick = { picking.opened = if (row.open) picking.opened - key(node) else picking.opened + key(node) }) {
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
