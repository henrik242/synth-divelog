package no.synth.divelog.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearService
import no.synth.divelog.core.model.UNSAVED_ID
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.components.DateField
import no.synth.divelog.ui.components.SelectField
import no.synth.divelog.ui.components.parseDate
import kotlin.time.Clock

internal fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** One piece of gear, saved as it is edited, and its services. [onDeleted] runs after a delete. */
@Composable
internal fun GearItemScreen(container: AppContainer, gearId: Long, onDeleted: () -> Unit) {
    val item = remember(gearId) { container.gear.get(gearId) } ?: run {
        Text("Gear not found", Modifier.padding(16.dp)); return
    }
    val original = remember(gearId) { GearDraft.of(item) }
    var draft by remember(gearId) { mutableStateOf(original) }
    // As last saved, so a half-typed date or price keeps the saved value.
    var saved by remember(gearId) { mutableStateOf(item) }
    val services = observe(gearId, read = { container.gear.services(gearId) }, flow = { container.gear.servicesFlow(gearId) })
    var editingService by remember(gearId) { mutableStateOf<GearService?>(null) }
    var confirmDelete by remember(gearId) { mutableStateOf(false) }

    fun change(next: GearDraft) {
        draft = next
        saved = next.applyTo(saved)
        container.gear.update(saved)
    }

    editingService?.let { service ->
        ServiceDialog(
            service,
            onSave = {
                if (it.id == UNSAVED_ID) container.gear.addService(it) else container.gear.updateService(it)
                editingService = null
            },
            onDismiss = { editingService = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${saved.name}?") },
            text = { Text("Its service history goes too.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; container.gear.delete(gearId); onDeleted() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (draft != original) TextButton(onClick = { draft = original; saved = item; container.gear.update(item) }) { Text("Undo changes") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        OutlinedTextField(
            draft.name,
            { change(draft.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            isError = draft.name.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        SelectField(
            label = "Kind",
            value = draft.kind?.label ?: "",
            options = listOf<GearKind?>(null) + GearKind.entries,
            optionLabel = { it?.label ?: "None" },
            onSelect = { change(draft.copy(kind = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput("Brand", draft.brand, Modifier.weight(1f)) { change(draft.copy(brand = it)) }
            TextInput("Model", draft.model, Modifier.weight(1f)) { change(draft.copy(model = it)) }
        }
        TextInput("Serial number", draft.serialNumber, Modifier.fillMaxWidth()) { change(draft.copy(serialNumber = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(draft.purchased, { change(draft.copy(purchased = it)) }, "Purchased", Modifier.weight(1f), optional = true)
            OutlinedTextField(
                draft.price,
                { change(draft.copy(price = it.filter { c -> c.isDigit() || c == '.' || c == ',' })) },
                label = { Text("Price") },
                singleLine = true,
                isError = !draft.priceValid(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        TextInput("Shop", draft.shop, Modifier.fillMaxWidth()) { change(draft.copy(shop = it)) }
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = !draft.retired,
                role = Role.Switch,
                onValueChange = { change(draft.copy(retired = !it)) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("In use")
                Text(
                    "Turn off for gear you sold, lost or no longer use. It is hidden from the list unless shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = !draft.retired, onCheckedChange = null)
        }
        OutlinedTextField(
            draft.notes,
            { change(draft.copy(notes = it)) },
            label = { Text("Notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Services", style = MaterialTheme.typography.titleSmall)
                services.firstOrNull()?.let { last ->
                    val day = dateOf(last.epochDay)
                    Text(
                        "Last serviced $day, ${timeSince(day, today())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = { editingService = GearService(gearId = gearId, epochDay = epochDay(today())) }) {
                Text("Log service")
            }
        }
        if (services.isEmpty()) {
            Text("Not serviced yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        services.forEach { service ->
            ListItem(
                headlineContent = { Text(dateOf(service.epochDay).toString()) },
                supportingContent = service.notes?.let { { Text(it) } },
                trailingContent = {
                    IconButton(onClick = { container.gear.deleteService(service.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete service")
                    }
                },
                modifier = Modifier.clickable { editingService = service },
            )
        }
    }
}

@Composable
private fun TextInput(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier)
}

/** The date and notes of a new or existing [service]. */
@Composable
private fun ServiceDialog(service: GearService, onSave: (GearService) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(dateOf(service.epochDay).toString()) }
    var notes by remember { mutableStateOf(service.notes.orEmpty()) }
    val day = parseDate(date)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (service.id == UNSAVED_ID) "Log service" else "Service") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(date, { date = it }, "Date", Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { day?.let { onSave(service.copy(epochDay = epochDay(it), notes = notes.ifBlank { null })) } },
                enabled = day != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
