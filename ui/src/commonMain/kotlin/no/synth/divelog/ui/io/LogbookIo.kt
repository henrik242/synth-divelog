package no.synth.divelog.ui.io

import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveFormat
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.GitLogFormat
import no.synth.divelog.core.formats.MacDiveXml
import no.synth.divelog.core.formats.SiteRef
import no.synth.divelog.core.formats.SubsurfaceXml
import no.synth.divelog.core.formats.TankEntry
import no.synth.divelog.core.formats.UddfFormat
import no.synth.divelog.core.db.ImportResult
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.shearwater.PredatorDump
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Tank
import no.synth.divelog.ui.AppContainer

data class ImportCounts(val imported: Int, val merged: Int, val skipped: Int)

/** Exports the logbook to, and imports it from, the file formats. */
class LogbookIo(private val container: AppContainer) {

    fun exportAll(format: DiveFormat): String = format.write(buildDiveLog())

    /** Serialize the whole logbook into the cloud git storage format. */
    fun exportCloudTree(): Map<String, String> = GitLogFormat().write(buildDiveLog())

    private fun buildDiveLog(): DiveLog {
        val entries = container.dives.allDives().map { dive ->
            val computers = container.dives.recordsForDive(dive.id).map { r ->
                val device = r.deviceId?.let { container.devices.get(it) }
                ComputerEntry(
                    model = device?.let { ComputerNames.fullName(it) },
                    serial = device?.serial,
                    maxDepthMm = r.maxDepthMm,
                    samples = container.dives.samplesForRecord(r.id),
                    events = container.dives.eventsForRecord(r.id),
                )
            }
            val site = dive.siteId?.let { siteRef(it) }
            val tanks = container.gases.tanksForDive(dive.id).map { t ->
                val gas = t.gasMixId?.let { container.gases.gasMix(it) }
                TankEntry(
                    index = t.index,
                    volumeMl = t.volumeMl,
                    workingPressureMbar = t.workingPressureMbar,
                    startPressureMbar = t.startPressureMbar,
                    endPressureMbar = t.endPressureMbar,
                    o2Permille = gas?.o2Permille,
                    hePermille = gas?.hePermille,
                )
            }
            DiveEntry(
                number = dive.number,
                startEpochSeconds = dive.startEpochSeconds,
                utcOffsetSeconds = dive.utcOffsetSeconds,
                durationSeconds = dive.durationSeconds,
                maxDepthMm = dive.maxDepthMm,
                meanDepthMm = dive.meanDepthMm,
                waterTempMk = dive.waterTempMk,
                airTempMk = dive.airTempMk,
                notes = dive.notes,
                rating = dive.rating,
                visibility = dive.visibility,
                site = site,
                buddies = container.buddies.buddiesForDive(dive.id).map { it.name },
                tags = container.tags.tagsForDive(dive.id).map { it.name },
                tanks = tanks,
                computers = computers,
            )
        }
        return DiveLog(entries)
    }

    /**
     * Detect and import file [text], returning a user-facing summary. [onProgress] is
     * called per dive with the running count and the total so a caller can show progress.
     */
    fun importMessage(text: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): String {
        val format = detect(text) ?: return "Unrecognized file (expected Subsurface XML, UDDF or MacDive XML)"
        val counts = import(format, text, onProgress)
        return "Imported ${counts.imported}, merged ${counts.merged}, skipped ${counts.skipped} (${format.displayName})"
    }

    /** Import the files pulled from the cloud git repo, returning a user-facing summary. */
    fun cloudImportMessage(
        files: Map<String, String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String {
        val log = GitLogFormat().read(files)
        if (log.dives.isEmpty()) return "No dives found in the cloud logbook (${files.size} files)"
        val counts = importLog(log, "subsurface-cloud", onProgress = onProgress)
        return "Pulled: imported ${counts.imported}, skipped ${counts.skipped}"
    }

    fun import(
        format: DiveFormat,
        text: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportCounts =
        // A time overlap in a file import means the same dive logged twice (MacDive exports
        // repeat dives), so attach it to the existing dive rather than duplicating it.
        importLog(format.read(text), format.id, autoMerge = true, onProgress = onProgress)

    private fun importLog(
        log: DiveLog,
        formatId: String,
        autoMerge: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportCounts {
        val total = log.dives.size
        var processed = 0
        var imported = 0
        var merged = 0
        var skipped = 0
        for (entry in log.dives) {
            val fingerprint = "import:$formatId:${entry.startEpochSeconds}:${entry.number ?: 0}:${entry.maxDepthMm ?: 0}"
            val primary = entry.computers.firstOrNull { it.samples.isNotEmpty() } ?: entry.computers.firstOrNull()
            // Resolve the device from this dive's computer so the same dive logged on two
            // computers merges into one dive that keeps both records.
            val deviceId = importDeviceId(primary?.model, primary?.serial)
            val incoming = IncomingDive(
                deviceId = deviceId,
                number = entry.number,
                startEpochSeconds = entry.startEpochSeconds,
                utcOffsetSeconds = entry.utcOffsetSeconds,
                durationSeconds = entry.durationSeconds,
                maxDepthMm = entry.maxDepthMm,
                meanDepthMm = entry.meanDepthMm,
                waterTempMk = entry.waterTempMk,
                airTempMk = entry.airTempMk,
                rawData = ByteArray(0), // raw not retained for file imports
                rawFormatId = formatId,
                fingerprint = fingerprint,
                samples = primary?.samples ?: emptyList(),
                events = primary?.events ?: emptyList(),
            )
            when (val result = container.dives.import(incoming) { autoMerge }) {
                is ImportResult.SkippedDuplicate -> {
                    // Already logged (e.g. downloaded): still take what this copy adds, such as
                    // the site, buddies or tank sizes, without replacing anything.
                    container.dives.record(result.existingRecordId)?.let { applyMetadata(it.diveId, entry) }
                    skipped++
                }
                is ImportResult.CreatedDive -> {
                    applyMetadata(result.diveId, entry)
                    attachExtraComputers(result.diveId, entry)
                    imported++
                }
                is ImportResult.AttachedToDive -> {
                    // Fill any metadata the existing dive was missing from this copy.
                    applyMetadata(result.diveId, entry)
                    attachExtraComputers(result.diveId, entry)
                    merged++
                }
            }
            processed++
            onProgress(processed, total)
        }
        return ImportCounts(imported, merged, skipped)
    }

    /**
     * The device for a logbook's computer [name] and [serial]: the same computer already
     * downloaded or imported (by serial, else by vendor and model), or a new one. A dive
     * naming no computer goes to a shared "Unknown computer".
     */
    private fun importDeviceId(name: String?, serial: String?): Long {
        val (vendor, model) = ComputerNames.split(name)
        return container.devices.getOrCreate(Device(vendor = vendor, model = model, serial = serial))
    }

    /**
     * Apply a dive's file metadata, keeping whatever the dive already has and only filling
     * the gaps. On a merge (the same dive imported twice, e.g. from two computers) this means
     * site, buddies, notes, rating and the rest are taken from whichever copy actually has
     * them. Tanks merge by gas (see [mergeTanks]).
     */
    private fun applyMetadata(diveId: Long, entry: DiveEntry) {
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
                val siteId = container.sites.getOrCreateSite(s.country ?: "Unknown", s.place ?: "Unknown", s.name)
                if (s.latitude != null && s.longitude != null) {
                    container.sites.site(siteId)?.let {
                        container.sites.updateSite(it.copy(latitude = s.latitude, longitude = s.longitude))
                    }
                }
                container.dives.setSite(diveId, siteId)
            }
        }

        // Buddies: union, adding only those not already on the dive.
        val linked = currentBuddies.map { it.name }.toSet()
        entry.buddies.filter { it.isNotBlank() && it !in linked }.forEach { name ->
            val id = container.buddies.all().firstOrNull { it.name == name }?.id ?: container.buddies.add(name)
            container.buddies.linkToDive(diveId, id)
        }

        // Tags: union, adding only those not already on the dive.
        val linkedTags = currentTags.map { it.name }.toSet()
        entry.tags.filter { it.isNotBlank() && it !in linkedTags }.forEach { name ->
            container.tags.linkToDive(diveId, container.tags.getOrCreate(name))
        }

        mergeTanks(diveId, entry)
    }

    /**
     * The log's tanks onto the dive: each matches an unclaimed tank of the same gas, filling
     * in the size and pressures it lacks (a downloaded dive knows its gases but not its
     * tanks); the rest are added. A log with gases but no tanks adds the gases.
     */
    private fun mergeTanks(diveId: Long, entry: DiveEntry) {
        val incoming = entry.tanks.ifEmpty {
            entry.gasMixes.mapIndexed { i, g -> TankEntry(index = i, o2Permille = g.o2Permille, hePermille = g.hePermille) }
        }
        if (incoming.isEmpty()) return
        val existing = container.gases.tanksForDive(diveId).toMutableList()
        fun gasOf(t: Tank) = t.gasMixId?.let { container.gases.gasMix(it) }?.let { it.o2Permille to it.hePermille }
        val gasesOnDive = existing.mapNotNull { gasOf(it) }.toMutableSet()
        var nextIndex = (existing.maxOfOrNull { it.index } ?: -1) + 1
        for (t in incoming) {
            // Some logs carry 0xFF "unknown" bytes as a mix (255 %); that is no gas at all.
            val gas = t.o2Permille?.let { it to (t.hePermille ?: 0) }
                ?.takeIf { (o2, he) -> o2 in 1..1_000 && he in 0..1_000 && o2 + he <= 1_000 }
            val bare = t.volumeMl == null && t.startPressureMbar == null && t.endPressureMbar == null
            // A tank that only names a gas adds nothing once the dive has that gas.
            if (bare && (gas == null || gas in gasesOnDive)) continue
            val match = existing.firstOrNull { gasOf(it) == gas }
            if (match != null) {
                existing.remove(match)
                val filled = match.copy(
                    volumeMl = match.volumeMl ?: t.volumeMl,
                    workingPressureMbar = match.workingPressureMbar ?: t.workingPressureMbar,
                    startPressureMbar = match.startPressureMbar ?: t.startPressureMbar,
                    endPressureMbar = match.endPressureMbar ?: t.endPressureMbar,
                )
                if (filled != match) container.gases.updateTank(filled)
            } else {
                container.gases.addTank(
                    Tank(
                        diveId = diveId,
                        index = nextIndex++,
                        volumeMl = t.volumeMl,
                        workingPressureMbar = t.workingPressureMbar,
                        startPressureMbar = t.startPressureMbar,
                        endPressureMbar = t.endPressureMbar,
                        gasMixId = gas?.let { (o2, he) -> container.gases.getOrCreateGasMix(o2, he) },
                    ),
                )
                gas?.let { gasesOnDive.add(it) }
            }
        }
    }

    /** How informative a site is: coordinates count most, a real country/place next. */
    private fun siteScore(hasCoordinates: Boolean, country: String?, place: String?): Int {
        var score = 0
        if (hasCoordinates) score += 2
        val realCountry = country?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        val realPlace = place?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        if (realCountry != null || realPlace != null) score += 1
        return score
    }

    /** Extra richness a copy carries beyond its site: having buddies, notes and tags. */
    private fun extraScore(hasBuddies: Boolean, hasNotes: Boolean, hasTags: Boolean): Int =
        (if (hasBuddies) 1 else 0) + (if (hasNotes) 1 else 0) + (if (hasTags) 1 else 0)

    private fun attachExtraComputers(diveId: Long, entry: DiveEntry) {
        entry.computers.drop(1).forEachIndexed { i, computer ->
            container.dives.attachToDive(
                IncomingDive(
                    deviceId = importDeviceId(computer.model, computer.serial),
                    startEpochSeconds = entry.startEpochSeconds,
                    utcOffsetSeconds = entry.utcOffsetSeconds,
                    durationSeconds = entry.durationSeconds,
                    maxDepthMm = computer.maxDepthMm,
                    rawData = ByteArray(0),
                    rawFormatId = "import-extra",
                    fingerprint = "import-extra:$diveId:$i",
                    samples = computer.samples,
                    events = computer.events,
                ),
                diveId,
            )
        }
    }

    private fun siteRef(siteId: Long): SiteRef? {
        val site = container.sites.site(siteId) ?: return null
        val place = container.sites.place(site.placeId)
        val country = place?.let { container.sites.country(it.countryId) }
        return SiteRef(
            name = site.name,
            country = country?.name,
            place = place?.name,
            latitude = site.latitude,
            longitude = site.longitude,
        )
    }

    /**
     * Re-parse every stored dive from its saved raw download, rebuilding samples,
     * events and summaries while keeping user-entered fields (number, notes,
     * rating, site). Lets a parser improvement reach already-imported dives without
     * a re-download. File imports keep no raw data, so they are left untouched.
     * Returns the number of records re-parsed.
     */
    fun reparseAll(): Int {
        val parser = PredatorParser()
        var count = 0
        for (dive in container.dives.allDives()) {
            for (record in container.dives.recordsForDive(dive.id)) {
                if (record.rawFormatId !in REPARSEABLE_FORMATS) continue
                val raw = container.dives.rawData(record.id) ?: continue
                val incoming = runCatching {
                    parser.parse(RawDive(record.fingerprint, raw, record.rawFormatId))
                }.getOrNull() ?: continue
                container.dives.reparseRecord(record.id, incoming)
                count++
            }
        }
        return count
    }

    companion object {
        private val REPARSEABLE_FORMATS = setOf(PredatorDump.FORMAT_ID, PredatorParser.PETREL_FORMAT_ID)

        fun formats(): List<DiveFormat> = listOf(SubsurfaceXml(), UddfFormat(), MacDiveXml())

        /** Guess the format from the file content. */
        fun detect(text: String): DiveFormat? = when {
            text.contains("mac-dive") -> MacDiveXml()
            text.contains("<uddf") -> UddfFormat()
            text.contains("<divelog") -> SubsurfaceXml()
            else -> null
        }
    }
}
