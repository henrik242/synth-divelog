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
                ComputerEntry(
                    model = r.deviceId?.let { container.devices.get(it)?.model },
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
    fun cloudImportMessage(files: Map<String, String>): String {
        val log = GitLogFormat().read(files)
        val counts = importLog(log, "subsurface-cloud", "Subsurface cloud")
        return "Pulled: imported ${counts.imported}, skipped ${counts.skipped}"
    }

    fun import(
        format: DiveFormat,
        text: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportCounts =
        // A time overlap in a file import means the same dive logged twice (MacDive exports
        // repeat dives), so attach it to the existing dive rather than duplicating it.
        importLog(format.read(text), format.id, format.displayName, autoMerge = true, onProgress = onProgress)

    private fun importLog(
        log: DiveLog,
        formatId: String,
        displayName: String,
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
            // computers merges into one dive that keeps both records. A dive with no computer
            // info falls back to a single generic imported device.
            val deviceId = importDeviceId(primary?.model, displayName)
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
                is ImportResult.SkippedDuplicate -> skipped++
                is ImportResult.CreatedDive -> {
                    applyMetadata(result.diveId, entry, isNew = true)
                    attachExtraComputers(result.diveId, entry, displayName)
                    imported++
                }
                is ImportResult.AttachedToDive -> {
                    // Fill any metadata the existing dive was missing from this copy.
                    applyMetadata(result.diveId, entry, isNew = false)
                    attachExtraComputers(result.diveId, entry, displayName)
                    merged++
                }
            }
            processed++
            onProgress(processed, total)
        }
        return ImportCounts(imported, merged, skipped)
    }

    /**
     * Resolve (or create) an imported device for a computer [model]. Keyed on a synthetic
     * "import:<model>" Bluetooth address so `getOrCreate` dedupes per computer, keeping the
     * same computer's records on one device. Falls back to a single generic device keyed on
     * the format name when a dive carries no computer model.
     */
    private fun importDeviceId(model: String?, displayName: String): Long {
        val name = model ?: displayName
        return container.devices.getOrCreate(
            Device(vendor = "Imported", model = name, bluetoothAddress = "import:$name"),
        )
    }

    /**
     * Apply a dive's file metadata, keeping whatever the dive already has and only filling
     * the gaps. On a merge (the same dive imported twice, e.g. from two computers) this means
     * site, buddies, notes, rating and the rest are taken from whichever copy actually has
     * them. Tanks are added for a new dive only, so a merge does not duplicate them.
     */
    private fun applyMetadata(diveId: Long, entry: DiveEntry, isNew: Boolean) {
        val dive = container.dives.getDive(diveId) ?: return

        // Scalar fields: keep the dive's value, else take the incoming one.
        val filled = dive.copy(
            number = dive.number ?: entry.number,
            maxDepthMm = dive.maxDepthMm ?: entry.maxDepthMm,
            meanDepthMm = dive.meanDepthMm ?: entry.meanDepthMm,
            waterTempMk = dive.waterTempMk ?: entry.waterTempMk,
            airTempMk = dive.airTempMk ?: entry.airTempMk,
            notes = dive.notes?.takeIf { it.isNotBlank() } ?: entry.notes?.takeIf { it.isNotBlank() },
            rating = dive.rating ?: entry.rating,
            visibility = dive.visibility ?: entry.visibility,
        )
        if (filled != dive) container.dives.updateDive(filled)

        // Site: only when the dive has none yet.
        if (dive.siteId == null) {
            entry.site?.let { s ->
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
        val linked = container.buddies.buddiesForDive(diveId).map { it.name }.toSet()
        entry.buddies.filter { it.isNotBlank() && it !in linked }.forEach { name ->
            val id = container.buddies.all().firstOrNull { it.name == name }?.id ?: container.buddies.add(name)
            container.buddies.linkToDive(diveId, id)
        }

        // Tanks belong to the dive; add them for a new dive only so a merge does not duplicate.
        if (isNew) {
            for (tank in entry.tanks) {
                val o2 = tank.o2Permille
                val gasId = if (o2 != null) container.gases.getOrCreateGasMix(o2, tank.hePermille ?: 0) else null
                container.gases.addTank(
                    Tank(
                        diveId = diveId,
                        index = tank.index,
                        volumeMl = tank.volumeMl,
                        workingPressureMbar = tank.workingPressureMbar,
                        startPressureMbar = tank.startPressureMbar,
                        endPressureMbar = tank.endPressureMbar,
                        gasMixId = gasId,
                    ),
                )
            }
        }
    }

    private fun attachExtraComputers(diveId: Long, entry: DiveEntry, displayName: String) {
        entry.computers.drop(1).forEachIndexed { i, computer ->
            container.dives.attachToDive(
                IncomingDive(
                    deviceId = importDeviceId(computer.model, displayName),
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
