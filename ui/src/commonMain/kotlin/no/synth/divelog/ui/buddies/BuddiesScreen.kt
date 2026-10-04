package no.synth.divelog.ui.buddies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.BackHeader
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.dive.DiveListWithDetail

@Composable
fun BuddiesSection(container: AppContainer, unitSystem: UnitSystem, dataVersion: Int) {
    var openBuddy by remember(dataVersion) { mutableStateOf<Buddy?>(null) }
    val current = openBuddy
    if (current != null) {
        val dives = remember(current.id) { container.buddies.divesForBuddy(current.id) }
        Column(Modifier.fillMaxSize()) {
            BackHeader(current.name, onBack = { openBuddy = null })
            Text("${dives.size} shared dive(s)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
            DiveListWithDetail(container, dives, unitSystem)
        }
        return
    }

    val buddies = remember(dataVersion) { container.buddies.all() }
    if (buddies.isEmpty()) {
        EmptyState("No buddies yet. Add them when editing a dive.", Icons.Outlined.Group)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(buddies) { buddy ->
            Text(buddy.name, Modifier.fillMaxWidth().clickable { openBuddy = buddy }.padding(16.dp))
            HorizontalDivider()
        }
    }
}
