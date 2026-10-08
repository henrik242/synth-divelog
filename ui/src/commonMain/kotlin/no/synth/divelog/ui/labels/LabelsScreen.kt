package no.synth.divelog.ui.labels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.components.NamedItem
import no.synth.divelog.ui.dive.DiveList

/** A named label on dives (a buddy or a tag) and the repository calls the shared screens need. */
class LabelKind(
    val noun: String,
    val emptyText: String,
    val emptyIcon: ImageVector,
    val countText: (Int) -> String,
    val all: () -> List<NamedItem>,
    val allFlow: () -> Flow<List<NamedItem>>,
    val get: (Long) -> NamedItem?,
    val rename: (id: Long, name: String) -> Unit,
    val delete: (Long) -> Unit,
    val dives: (Long) -> List<Dive>,
) {
    companion object {
        fun buddies(container: AppContainer) = LabelKind(
            noun = "Buddy",
            emptyText = "No buddies yet. Add them when editing a dive.",
            emptyIcon = Icons.Outlined.Group,
            countText = { "$it shared dive(s)" },
            all = { container.buddies.all().map { NamedItem(it.id, it.name) } },
            allFlow = { container.buddies.allFlow().map { list -> list.map { NamedItem(it.id, it.name) } } },
            get = { id -> container.buddies.get(id)?.let { NamedItem(it.id, it.name) } },
            rename = container.buddies::rename,
            delete = container.buddies::delete,
            dives = container.buddies::divesForBuddy,
        )

        fun tags(container: AppContainer) = LabelKind(
            noun = "Tag",
            emptyText = "No tags yet. Add them when editing a dive.",
            emptyIcon = Icons.Outlined.Sell,
            countText = { "$it tagged dive(s)" },
            all = { container.tags.all().map { NamedItem(it.id, it.name) } },
            allFlow = { container.tags.allFlow().map { list -> list.map { NamedItem(it.id, it.name) } } },
            get = { id -> container.tags.get(id)?.let { NamedItem(it.id, it.name) } },
            rename = container.tags::rename,
            delete = container.tags::delete,
            dives = container.tags::divesForTag,
        )
    }
}

/** Every label of [kind]; tapping one hands its id to [onOpen]. */
@Composable
fun LabelList(kind: LabelKind, onOpen: (Long) -> Unit) {
    val labels = observe(kind, read = kind.all, flow = kind.allFlow)
    if (labels.isEmpty()) {
        EmptyState(kind.emptyText, kind.emptyIcon)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(labels) { label ->
            Text(label.name, Modifier.fillMaxWidth().clickable { onOpen(label.id) }.padding(16.dp))
            HorizontalDivider()
        }
    }
}

/** One label: its name (renamed as it is typed), a delete action and its dives. [onDeleted] runs after a delete. */
@Composable
fun LabelDetail(
    kind: LabelKind,
    id: Long,
    unitSystem: UnitSystem,
    container: AppContainer,
    onOpenDive: (Long) -> Unit,
    onDeleted: () -> Unit,
) {
    val label = remember(kind, id) { kind.get(id) } ?: run {
        Text("${kind.noun} not found", Modifier.padding(16.dp)); return
    }
    var name by remember(kind, id) { mutableStateOf(label.name) }
    var menuOpen by remember(kind, id) { mutableStateOf(false) }
    val dives = remember(kind, id) { kind.dives(id) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                name,
                { name = it; if (it.isNotBlank()) { kind.rename(id, it.trim()) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "${kind.noun} actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; kind.delete(id); onDeleted() },
                    )
                }
            }
        }
        Text(kind.countText(dives.size), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        DiveList(container, dives, unitSystem, onOpenDive)
    }
}
