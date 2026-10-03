package no.synth.divelog.ui.io

import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveFormat
import no.synth.divelog.core.formats.DiveLog
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

data class ImportCounts(val imported: Int, val skipped: Int)

/** Exports the logbook to, and imports it from, the file formats. */
class LogbookIo(private val container: AppContainer) {

    fun exportAll(format: DiveFormat): String {
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
        return format.write(DiveLog(entries))
    }

    fun import(format: DiveFormat, text: String): ImportCounts {
        val log = format.read(text)
        val deviceId = container.devices.getOrCreate(Device(vendor = "Imported", model = format.displayName))
        var imported = 0
        var skipped = 0
        for (entry in log.dives) {
            val fingerprint = "import:${format.id}:${entry.startEpochSeconds}:${entry.number ?: 0}:${entry.maxDepthMm ?: 0}"
            val primary = entry.computers.firstOrNull { it.samples.isNotEmpty() } ?: entry.computers.firstOrNull()
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
                rawFormatId = format.id,
                fingerprint = fingerprint,
                samples = primary?.samples ?: emptyList(),
                events = primary?.events ?: emptyList(),
            )
            when (val result = container.dives.import(incoming) { false }) {
                is ImportResult.SkippedDuplicate -> skipped++
                is ImportResult.CreatedDive -> {
                    applyMetadata(result.diveId, entry)
                    attachExtraComputers(result.diveId, entry, deviceId)
                    imported++
                }
                is ImportResult.AttachedToDive -> imported++
            }
        }
        return ImportCounts(imported, skipped)
    }

    private fun applyMetadata(diveId: Long, entry: DiveEntry) {
        entry.site?.let { s ->
            val siteId = container.sites.getOrCreateSite(
                s.country ?: "Unknown",
                s.place ?: "Unknown",
                s.name,
            )
            if (s.latitude != null && s.longitude != null) {
                container.sites.site(siteId)?.let {
                    container.sites.updateSite(it.copy(latitude = s.latitude, longitude = s.longitude))
                }
            }
            container.dives.setSite(diveId, siteId)
        }
        entry.buddies.forEach { name ->
            val id = container.buddies.all().firstOrNull { it.name == name }?.id ?: container.buddies.add(name)
            container.buddies.linkToDive(diveId, id)
        }
        for (tank in entry.tanks) {
            val gasId = if (tank.o2Permille != null) {
                container.gases.getOrCreateGasMix(tank.o2Permille!!, tank.hePermille ?: 0)
            } else {
                null
            }
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

    private fun attachExtraComputers(diveId: Long, entry: DiveEntry, deviceId: Long) {
        entry.computers.drop(1).forEachIndexed { i, computer ->
            container.dives.attachToDive(
                IncomingDive(
                    deviceId = deviceId,
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

        fun formats(): List<DiveFormat> = listOf(SubsurfaceXml(), UddfFormat())

        /** Guess the format from the file content. */
        fun detect(text: String): DiveFormat? = when {
            text.contains("<uddf") -> UddfFormat()
            text.contains("<divelog") -> SubsurfaceXml()
            else -> null
        }
    }
}
