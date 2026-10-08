package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.TankEntry
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.Tank

/** Country or place name for a site imported without one. */
internal const val UNKNOWN_PLACE = "Unknown"

/**
 * How a logbook copy of a dive folds into the dive already stored: fill what the dive lacks,
 * never replace what it has, and take the richer site.
 */
internal class LogbookMerger(private val container: AppContainer) {

    /**
     * Apply a dive's file metadata, keeping whatever the dive already has and only filling
     * the gaps. On a merge (the same dive imported twice, e.g. from two computers) this means
     * site, buddies, notes, rating and the rest are taken from whichever copy actually has
     * them. Tanks merge by gas (see [mergeTanks]).
     */
    fun applyMetadata(diveId: Long, entry: DiveEntry) {
        val dive = container.dives.getDive(diveId) ?: return
        val currentBuddies = container.buddies.buddiesForDive(diveId)
        val currentTags = container.tags.tagsForDive(diveId)

        // Scalar fields: keep the dive's value, else take the incoming one. Notes are not just
        // filled - the longer (richer) note wins, so a copy that actually has notes is kept.
        val bestNotes = listOfNotNull(
            dive.notes?.takeIf { it.isNotBlank() },
            entry.notes?.takeIf { it.isNotBlank() },
        ).maxByOrNull { it.length }
        val filled = dive.copy(
            number = dive.number ?: entry.number,
            maxDepthMm = dive.maxDepthMm ?: entry.maxDepthMm,
            meanDepthMm = dive.meanDepthMm ?: entry.meanDepthMm,
            waterTempMk = dive.waterTempMk ?: entry.waterTempMk,
            airTempMk = dive.airTempMk ?: entry.airTempMk,
            notes = bestNotes,
            rating = dive.rating ?: entry.rating,
            visibility = dive.visibility ?: entry.visibility,
            visibilityRating = dive.visibilityRating ?: entry.visibilityRating,
        )
        if (filled != dive) container.dives.updateDive(filled)

        // Site: take the incoming one when the dive has none, when its site is richer
        // (coordinates and a real country/place outrank a bare placeholder like a MacDive
        // "(duplikat)" marker), or on a site tie when the incoming copy carries more buddies
        // and notes - so the copy with the real metadata wins.
        entry.site?.let { s ->
            val incomingSiteScore = siteScore(s.latitude != null && s.longitude != null, s.country, s.place)
            val currentSiteScore = dive.siteId?.let { id ->
                val existing = container.sites.site(id)
                val place = existing?.let { container.sites.place(it.placeId) }
                val country = place?.let { container.sites.country(it.countryId) }
                if (existing == null) 0 else siteScore(existing.latitude != null && existing.longitude != null, country?.name, place?.name)
            } ?: 0
            val incomingExtra = extraScore(entry.buddies.any { it.isNotBlank() }, !entry.notes.isNullOrBlank(), entry.tags.any { it.isNotBlank() })
            val currentExtra = extraScore(currentBuddies.isNotEmpty(), !dive.notes.isNullOrBlank(), currentTags.isNotEmpty())
            val richer = incomingSiteScore > currentSiteScore ||
                (incomingSiteScore == currentSiteScore && incomingExtra > currentExtra)
            if (dive.siteId == null || richer) {
                val siteId = container.sites.getOrCreateSite(s.country ?: UNKNOWN_PLACE, s.place ?: UNKNOWN_PLACE, s.name)
                if (s.latitude != null && s.longitude != null) {
                    container.sites.site(siteId)?.let {
                        container.sites.updateSite(it.copy(latitude = s.latitude, longitude = s.longitude))
                    }
                }
                container.dives.setSite(diveId, siteId)
            }
        }

        // Buddies and tags: union, adding only those not already on the dive.
        val linked = currentBuddies.map { it.name }.toSet()
        entry.buddies.filter { it.isNotBlank() && it !in linked }.forEach { name ->
            container.buddies.linkToDive(diveId, container.buddies.getOrCreate(name))
        }
        val linkedTags = currentTags.map { it.name }.toSet()
        entry.tags.filter { it.isNotBlank() && it !in linkedTags }.forEach { name ->
            container.tags.linkToDive(diveId, container.tags.getOrCreate(name))
        }

        mergeTanks(diveId, entry)
    }

    /**
     * The log's tanks onto the dive (see [no.synth.divelog.core.db.GasRepository.mergeTanks]):
     * a downloaded dive knows its gases but not its tanks, so a logbook fills in sizes and
     * pressures. A log with gases but no tanks adds the gases.
     */
    private fun mergeTanks(diveId: Long, entry: DiveEntry) {
        val incoming = entry.tanks.ifEmpty {
            entry.gasMixes.mapIndexed { i, g -> TankEntry(index = i, o2Permille = g.o2Permille, hePermille = g.hePermille) }
        }
        val tanks = incoming.map { t ->
            Tank(
                diveId = diveId,
                index = t.index,
                volumeMl = t.volumeMl,
                workingPressureMbar = t.workingPressureMbar,
                startPressureMbar = t.startPressureMbar,
                endPressureMbar = t.endPressureMbar,
                gasMixId = realGas(t)?.let { (o2, he) -> container.gases.getOrCreateGasMix(o2, he) },
            )
        }
        container.gases.mergeTanks(diveId, tanks)
    }

    /** A tank's gas as (o2, he) permille. Some logs carry 0xFF "unknown" bytes as a mix (255 %); that is no gas. */
    private fun realGas(t: TankEntry): Pair<Int, Int>? =
        t.o2Permille?.let { it to (t.hePermille ?: 0) }
            ?.takeIf { (o2, he) -> o2 in 1..1_000 && he in 0..1_000 && o2 + he <= 1_000 }

    /** How informative a site is: coordinates count most, a real country/place next. */
    private fun siteScore(hasCoordinates: Boolean, country: String?, place: String?): Int {
        var score = 0
        if (hasCoordinates) score += 2
        val realCountry = country?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN_PLACE, ignoreCase = true) }
        val realPlace = place?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN_PLACE, ignoreCase = true) }
        if (realCountry != null || realPlace != null) score += 1
        return score
    }

    /** Extra richness a copy carries beyond its site: having buddies, notes and tags. */
    private fun extraScore(hasBuddies: Boolean, hasNotes: Boolean, hasTags: Boolean): Int =
        (if (hasBuddies) 1 else 0) + (if (hasNotes) 1 else 0) + (if (hasTags) 1 else 0)
}
