package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Tag
import no.synth.divelog.core.model.Tank
import no.synth.divelog.core.model.UNSAVED_ID
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.CountryField
import no.synth.divelog.ui.components.NameChipsField
import no.synth.divelog.ui.components.NamedItem
import no.synth.divelog.ui.components.SiteField
import no.synth.divelog.ui.components.SiteOption
import kotlin.math.roundToLong

/**
 * Edits a dive. Every change is written straight to the database, so there is no save
 * step; leaving the screen (via the breadcrumb or back) returns to the detail. While the
 * dive differs from how it was opened, an "Undo changes" action restores the original
 * number, notes, site and buddies. [onCancel] is used only when the dive is missing.
 */
@Composable
fun DiveEditScreen(
    container: AppContainer,
    diveId: Long,
    unitSystem: UnitSystem,
    onCancel: () -> Unit,
) {
    val dive = remember(diveId) { container.dives.getDive(diveId) } ?: run { onCancel(); return }
    val originalBuddyIds = remember(diveId) { container.buddies.buddiesForDive(diveId).map { it.id }.toSet() }
    val originalTagIds = remember(diveId) { container.tags.tagsForDive(diveId).map { it.id }.toSet() }
    val originalTanks = remember(diveId) { container.gases.tanksForDive(diveId) }

    // Resolve a site's place and country for the picker detail line and the selected-site card.
    val countriesById = remember(diveId) { container.sites.countries().associateBy { it.id } }
    val placeCache = remember(diveId) { mutableMapOf<Long, Place?>() }
    fun placeOf(placeId: Long): Place? = placeCache.getOrPut(placeId) { container.sites.place(placeId) }
    fun locationLabel(placeId: Long): String {
        val place = placeOf(placeId)
        return listOfNotNull(place?.name, place?.let { countriesById[it.countryId]?.name }).joinToString(", ")
    }

    // Sites and buddies grow as new ones are added here, so they are editable state.
    var availableSites by remember(diveId) { mutableStateOf(container.sites.allSites()) }
    var availableBuddies by remember(diveId) { mutableStateOf(container.buddies.all()) }
    var availableTags by remember(diveId) { mutableStateOf(container.tags.all()) }
    val siteOptions = availableSites.map { SiteOption(it.id, it.name, locationLabel(it.placeId)) }

    val originalNumber = remember(diveId) { dive.number?.toString() ?: "" }
    val originalNotes = remember(diveId) { dive.notes ?: "" }

    var number by remember(diveId) { mutableStateOf(originalNumber) }
    var notes by remember(diveId) { mutableStateOf(originalNotes) }
    var selectedSiteId by remember(diveId) { mutableStateOf(dive.siteId) }
    var buddySelection by remember(diveId) { mutableStateOf(originalBuddyIds) }
    var tagSelection by remember(diveId) { mutableStateOf(originalTagIds) }
    var newCountry by remember(diveId) { mutableStateOf("") }
    var newPlace by remember(diveId) { mutableStateOf("") }
    var newSite by remember(diveId) { mutableStateOf("") }
    var addingSite by remember(diveId) { mutableStateOf(false) }

    // Tanks autosave like the rest; working pressure is not edited, so it is carried over.
    fun draftOf(tank: Tank): TankDraft {
        val gas = tank.gasMixId?.let { container.gases.gasMix(it) }
        return TankDraft.of(tank, gas?.o2Permille, gas?.hePermille, unitSystem)
    }
    val originalDrafts = remember(diveId) { originalTanks.map(::draftOf) }
    var tankDrafts by remember(diveId) { mutableStateOf(originalDrafts) }

    fun saveTank(index: Int, draft: TankDraft): TankDraft {
        val kept = originalTanks.firstOrNull { it.id == draft.id }
        val tank = Tank(
            id = draft.id ?: UNSAVED_ID,
            diveId = diveId,
            index = index,
            volumeMl = draft.volumeMl(),
            workingPressureMbar = kept?.workingPressureMbar,
            startPressureMbar = draft.startMbar(unitSystem),
            endPressureMbar = draft.endMbar(unitSystem),
            gasMixId = draft.gas()?.let { (o2, he) -> container.gases.getOrCreateGasMix(o2, he) },
        )
        return if (draft.id == null) {
            draft.copy(id = container.gases.addTank(tank))
        } else {
            container.gases.updateTank(tank)
            draft
        }
    }

    // Write the dive's own fields; buddy links are written as they are toggled.
    fun persistDive() {
        container.dives.updateDive(
            dive.copy(
                number = number.trim().toIntOrNull(),
                notes = notes.ifBlank { null },
                siteId = selectedSiteId,
            ),
        )
    }

    fun toggleBuddy(id: Long) {
        if (id in buddySelection) {
            buddySelection = buddySelection - id
            container.buddies.unlinkFromDive(diveId, id)
        } else {
            buddySelection = buddySelection + id
            container.buddies.linkToDive(diveId, id)
        }
    }

    fun toggleTag(id: Long) {
        if (id in tagSelection) {
            tagSelection = tagSelection - id
            container.tags.unlinkFromDive(diveId, id)
        } else {
            tagSelection = tagSelection + id
            container.tags.linkToDive(diveId, id)
        }
    }

    val hasEdits = number != originalNumber ||
        notes != originalNotes ||
        selectedSiteId != dive.siteId ||
        buddySelection != originalBuddyIds ||
        tagSelection != originalTagIds ||
        tankDrafts.map { it.copy(id = null) } != originalDrafts.map { it.copy(id = null) }

    fun undo() {
        number = originalNumber
        notes = originalNotes
        selectedSiteId = dive.siteId
        (buddySelection - originalBuddyIds).forEach { container.buddies.unlinkFromDive(diveId, it) }
        (originalBuddyIds - buddySelection).forEach { container.buddies.linkToDive(diveId, it) }
        buddySelection = originalBuddyIds
        (tagSelection - originalTagIds).forEach { container.tags.unlinkFromDive(diveId, it) }
        (originalTagIds - tagSelection).forEach { container.tags.linkToDive(diveId, it) }
        tagSelection = originalTagIds
        // Put the tanks back as they were: drop the current ones, re-add the originals.
        tankDrafts.mapNotNull { it.id }.forEach { container.gases.deleteTank(it) }
        val restored = originalTanks.map { it.copy(id = container.gases.addTank(it.copy(id = UNSAVED_ID))) }
        tankDrafts = restored.map(::draftOf)
        persistDive()
    }

    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        if (hasEdits) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { undo() }) { Text("Undo changes") }
            }
        }

        Column(
            Modifier.fillMaxWidth().weight(1f)
                // Tab / Shift-Tab move between fields instead of being typed or trapped in one.
                // The preview pass runs on this ancestor before the focused field handles the key.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                        focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                        true
                    } else {
                        false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                number,
                { number = it.filter { c -> c.isDigit() }; persistDive() },
                label = { Text("Dive number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                notes,
                { notes = it; persistDive() },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Site", style = MaterialTheme.typography.titleSmall)
                SiteField(
                    options = siteOptions,
                    selectedId = selectedSiteId,
                    onSelect = { selectedSiteId = it; persistDive() },
                    modifier = Modifier.fillMaxWidth(),
                )
                availableSites.firstOrNull { it.id == selectedSiteId }?.let { site ->
                    val detail = buildList {
                        locationLabel(site.placeId).takeIf { it.isNotEmpty() }?.let { add(it) }
                        coordinateLabel(site.latitude, site.longitude)?.let { add(it) }
                        site.notes?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    if (detail.isNotEmpty()) {
                        Text(
                            detail.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (addingSite) {
                    CountryField(newCountry, { newCountry = it }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(newPlace, { newPlace = it }, label = { Text("Place") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newSite, { newSite = it }, label = { Text("Site name") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = newCountry.isNotBlank() && newPlace.isNotBlank() && newSite.isNotBlank(),
                            onClick = {
                                val id = container.sites.getOrCreateSite(newCountry.trim(), newPlace.trim(), newSite.trim())
                                container.sites.site(id)?.let { s ->
                                    if (availableSites.none { it.id == s.id }) availableSites = availableSites + s
                                }
                                selectedSiteId = id
                                persistDive()
                                addingSite = false; newCountry = ""; newPlace = ""; newSite = ""
                            },
                        ) { Text("Add site") }
                        TextButton(onClick = { addingSite = false; newCountry = ""; newPlace = ""; newSite = "" }) {
                            Text("Cancel")
                        }
                    }
                } else {
                    TextButton(onClick = { addingSite = true }) { Text("Add a new site") }
                }
            }

            NameChipsField(
                title = "Buddies",
                fieldLabel = "Add buddy",
                selected = availableBuddies.filter { it.id in buddySelection }.map { NamedItem(it.id, it.name) },
                all = availableBuddies.map { NamedItem(it.id, it.name) },
                onAdd = { toggleBuddy(it.id) },
                onAddNew = { name ->
                    val id = container.buddies.add(name)
                    container.buddies.linkToDive(diveId, id)
                    availableBuddies = availableBuddies + Buddy(id, name)
                    buddySelection = buddySelection + id
                },
                onRemove = { toggleBuddy(it.id) },
            )

            NameChipsField(
                title = "Tags",
                fieldLabel = "Add tag",
                selected = availableTags.filter { it.id in tagSelection }.map { NamedItem(it.id, it.name) },
                all = availableTags.map { NamedItem(it.id, it.name) },
                onAdd = { toggleTag(it.id) },
                onAddNew = { name ->
                    val id = container.tags.getOrCreate(name)
                    container.tags.linkToDive(diveId, id)
                    if (availableTags.none { it.id == id }) availableTags = availableTags + Tag(id, name)
                    tagSelection = tagSelection + id
                },
                onRemove = { toggleTag(it.id) },
            )

            TanksEditor(
                drafts = tankDrafts,
                unitSystem = unitSystem,
                onChange = { i, d -> tankDrafts = tankDrafts.toMutableList().also { it[i] = saveTank(i, d) } },
                onRemove = { i ->
                    tankDrafts[i].id?.let { container.gases.deleteTank(it) }
                    tankDrafts = tankDrafts.filterIndexed { j, _ -> j != i }
                },
                onAdd = { tankDrafts = tankDrafts + saveTank(tankDrafts.size, TankDraft.blank()) },
            )
        }
    }
}

/** A site's coordinate as "lat, lon" at about a metre's precision, or null if not set. */
private fun coordinateLabel(latitude: Double?, longitude: Double?): String? {
    if (latitude == null || longitude == null) return null
    return "${round5(latitude)}, ${round5(longitude)}"
}

private fun round5(value: Double): Double = (value * 100_000).roundToLong() / 100_000.0
