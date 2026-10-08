package no.synth.divelog.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.components.BackHeader

/**
 * The dive computers the user has downloaded from: rename (autosaved), forget, and merge
 * one computer into another. [focusDeviceId] scrolls to and briefly highlights a device
 * (set when a dive-detail link opens this page); [onFocusConsumed] clears it afterwards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComputersScreen(
    container: AppContainer,
    onBack: () -> Unit,
    focusDeviceId: Long? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val devices = observe(container, read = container.devices::all, flow = container.devices::allFlow)
    val diveCounts = observe(container, read = container.devices::diveCounts, flow = container.devices::diveCountsFlow)
    var mergeSource by remember { mutableStateOf<Device?>(null) }

    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focusDeviceId, devices) {
        val id = focusDeviceId ?: return@LaunchedEffect
        if (devices.any { it.id == id }) {
            bringIntoView.bringIntoView()
            delay(2000)
            onFocusConsumed()
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackHeader("Dive computers", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (devices.isEmpty()) {
                Text("None yet. They are added when you download.", style = MaterialTheme.typography.bodyMedium)
            }
            devices.forEach { device ->
                key(device.id) {
                    val focused = device.id == focusDeviceId
                    ComputerRow(
                        device = device,
                        dives = diveCounts[device.id] ?: 0L,
                        highlighted = focused,
                        canMerge = devices.size > 1,
                        modifier = if (focused) Modifier.bringIntoViewRequester(bringIntoView) else Modifier,
                        onRename = { container.devices.update(device.copy(nickname = it.ifBlank { null })) },
                        onMerge = { mergeSource = device },
                        onForget = { container.devices.delete(device.id) },
                    )
                }
            }
        }
    }

    mergeSource?.let { source ->
        MergeDialog(
            source = source,
            targets = devices.filter { it.id != source.id },
            diveCounts = diveCounts,
            onConfirm = { target ->
                container.devices.merge(source.id, target.id)
                mergeSource = null
            },
            onDismiss = { mergeSource = null },
        )
    }
}

/** Display name: the nickname if set, else vendor and model. */
private fun Device.displayName(): String = nickname?.takeIf { it.isNotBlank() } ?: ComputerNames.fullName(this)

private fun Device.detail(dives: Long): String = buildList {
    if (!nickname.isNullOrBlank()) add(ComputerNames.fullName(this@detail))
    serial?.takeIf { it.isNotBlank() }?.let { add("Serial $it") }
    bluetoothAddress?.let { add(it) }
    add(if (dives == 1L) "1 dive" else "$dives dives")
}.joinToString(" - ")

@Composable
private fun ComputerRow(
    device: Device,
    dives: Long,
    canMerge: Boolean,
    onRename: (String) -> Unit,
    onMerge: () -> Unit,
    onForget: () -> Unit,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var nickname by remember { mutableStateOf(device.nickname ?: "") }
    val base = modifier.fillMaxWidth()
    val background = if (highlighted) {
        base.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(8.dp)
    } else {
        base
    }
    Column(background, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(device.displayName(), style = MaterialTheme.typography.titleSmall)
        Text(
            device.detail(dives),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Autosaves on every edit, like the rest of the app.
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it; onRename(it) },
            label = { Text("Nickname") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canMerge) OutlinedButton(onClick = onMerge) { Text("Merge into...") }
            OutlinedButton(
                onClick = onForget,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Forget") }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MergeDialog(
    source: Device,
    targets: List<Device>,
    diveCounts: Map<Long, Long>,
    onConfirm: (Device) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf<Device?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge ${source.displayName()} into") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "All its dives move to the chosen computer and this one is removed. " +
                        "The chosen computer keeps its own name and details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                targets.forEach { t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen?.id == t.id, onClick = { chosen = t })
                        Column {
                            Text(t.displayName(), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                t.detail(diveCounts[t.id] ?: 0L),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let(onConfirm) }, enabled = chosen != null) { Text("Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
