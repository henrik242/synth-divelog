package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.db.ImportDecision
import no.synth.divelog.core.db.ImportResult
import no.synth.divelog.core.divecomputer.DiveComputerKind
import no.synth.divelog.core.divecomputer.RawDive
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
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.IncomingDive
import kotlin.time.Clock

data class ImportCounts(val imported: Int, val merged: Int, val skipped: Int)

/** A record whose raw data its parser rejected on a re-parse. */
data class ReparseFailure(val recordId: Long, val message: String)

/** The outcome of [LogbookIo.reparseAllWithFailures]. */
data class ReparseResult(val reparsed: Int, val failures: List<ReparseFailure>)

/** An export to save or share: suggested file name, media type and content. */
class ExportFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** An export the user can pick: [id] goes to [LogbookIo.export]; the file gets [extension] and [mimeType]. */
data class ExportTarget(val id: String, val displayName: String, val extension: String, val mimeType: String)

/** An [ExportTarget] and how to write a log in it. */
private class Exporter(val target: ExportTarget, val write: (DiveLog) -> ByteArray)

/** Exports the logbook to, and imports it from, the file formats. */
class LogbookIo(private val container: AppContainer) {
    private val merger = LogbookMerger(container)

    /** The whole logbook as [format] text. */
    fun exportAll(format: DiveFormat): String = format.write(buildDiveLog())

    /**
     * The logbook in export [targetId] (see [exportTargets]), or null for an unknown id.
     * Only the dives in [diveIds] when given (ids with no dive are skipped), else the whole logbook.
     */
    fun export(targetId: String, diveIds: Set<Long>? = null): ExportFile? {
        val exporter = exporters().firstOrNull { it.target.id == targetId } ?: return null
        val log = buildDiveLog(diveIds)
        val count = log.dives.size
        val name = when {
            diveIds == null -> "synth-divelog"
            count == 1 -> "synth-divelog-1-dive"
            else -> "synth-divelog-$count-dives"
        }
        val target = exporter.target
        return ExportFile("$name.${target.extension}", target.mimeType, exporter.write(log))
    }

    /** Serialize the whole logbook into the cloud git storage format. */
    fun exportCloudTree(): Map<String, String> = GitLogFormat().write(buildDiveLog())

    /**
     * The logbook, or only the dives in [diveIds], read with one query per table rather than
     * per dive (an export runs on every cloud push), inside one transaction for a consistent
     * snapshot.
     */
    private fun buildDiveLog(diveIds: Set<Long>? = null): DiveLog = container.dives.inTransaction {
        val devices = container.devices.all().associateBy { it.id }
        val mixes = container.gases.allGasMixes()
        val recordsByDive = container.dives.recordsByDive()
        val samplesByRecord = container.dives.samplesByRecord()
        val eventsByRecord = container.dives.eventsByRecord()
        val tanksByDive = container.gases.tanksByDive()
        val buddiesByDive = container.buddies.buddyNamesByDive()
        val tagsByDive = container.tags.tagNamesByDive()
        val siteRefs = siteRefs()
        val dives = container.dives.allDives().filter { diveIds == null || it.id in diveIds }
        val entries = dives.map { dive ->
            val computers = recordsByDive[dive.id].orEmpty().map { r ->
                val device = r.deviceId?.let { devices[it] }
                ComputerEntry(
                    model = device?.let { ComputerNames.fullName(it) },
                    serial = device?.serial,
                    maxDepthMm = r.maxDepthMm,
                    samples = samplesByRecord[r.id].orEmpty(),
                    events = eventsByRecord[r.id].orEmpty(),
                    // Only a recording that starts or ends apart from the dive carries its own times.
                    startEpochSeconds = r.startEpochSeconds.takeIf { it != dive.startEpochSeconds },
                    durationSeconds = r.durationSeconds.takeIf { it != dive.durationSeconds },
                    diveMode = r.diveMode,
                    decoModel = r.decoModel,
                )
            }
            val tanks = tanksByDive[dive.id].orEmpty().map { t ->
                val gas = t.gasMixId?.let { mixes[it] }
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
                visibilityRating = dive.visibilityRating,
                site = dive.siteId?.let { siteRefs[it] },
                buddies = buddiesByDive[dive.id].orEmpty(),
                tags = tagsByDive[dive.id].orEmpty(),
                tanks = tanks,
                computers = computers,
            )
        }
        DiveLog(entries)
    }

    /**
     * Import a picked file: a Shearwater Cloud database export, or one of the text formats
     * (see [importMessage]). Returns a user-facing summary.
     */
    fun importFileMessage(bytes: ByteArray, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): String {
        if (!isSqliteFile(bytes)) return importMessage(bytes.decodeToString(), onProgress)
        val export = runCatching { readShearwaterCloudExport(bytes) }
            .getOrElse { return "Not a Shearwater Cloud export: ${it.message ?: it::class.simpleName}" }
        return importShearwaterCloud(export, onProgress)
    }

    internal fun importShearwaterCloud(
        export: ShearwaterCloudExport,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String {
        val read = ShearwaterCloudDives.read(export)
        if (read.dives.isEmpty()) return "No dives found in the Shearwater Cloud export"
        val counts = importItems(read.dives.map { ImportItem(it.entry, it.parsed) }, "shearwater-cloud", autoMerge = true, onProgress)
        val unreadable = if (read.unreadable > 0) ", ${read.unreadable} unreadable" else ""
        return "Imported ${counts.imported}, merged ${counts.merged}, skipped ${counts.skipped}$unreadable (Shearwater Cloud)"
    }

    /**
     * Detect and import file [text], returning a user-facing summary. [onProgress] is
     * called per dive with the running count and the total so a caller can show progress.
     */
    fun importMessage(text: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): String {
        val format = detect(text) ?: return "Unrecognized file (expected Subsurface XML, UDDF, MacDive XML or a Shearwater Cloud database)"
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

    /** A logbook entry to import, with its computer log already parsed when the source keeps it raw. */
    private class ImportItem(val entry: DiveEntry, val parsed: IncomingDive? = null)

    private fun importLog(
        log: DiveLog,
        formatId: String,
        autoMerge: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportCounts = importItems(log.dives.map { ImportItem(it) }, formatId, autoMerge, onProgress)

    private enum class Outcome { IMPORTED, MERGED, SKIPPED }

    private fun importItems(
        items: List<ImportItem>,
        formatId: String,
        autoMerge: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportCounts {
        var processed = 0
        var imported = 0
        var merged = 0
        var skipped = 0
        // A transaction per batch rather than per write; repository calls join it.
        for (batch in items.chunked(IMPORT_BATCH)) {
            container.dives.inTransaction {
                for (item in batch) {
                    when (importItem(item, formatId, autoMerge)) {
                        Outcome.IMPORTED -> imported++
                        Outcome.MERGED -> merged++
                        Outcome.SKIPPED -> skipped++
                    }
                    processed++
                    onProgress(processed, items.size)
                }
            }
        }
        return ImportCounts(imported, merged, skipped)
    }

    private fun importItem(item: ImportItem, formatId: String, autoMerge: Boolean): Outcome {
        val entry = item.entry
        if (entry.computers.isEmpty() && item.parsed == null) return importWithoutComputer(entry)
        val fingerprint = "import:$formatId:${entry.startEpochSeconds}:${entry.number ?: 0}:${entry.maxDepthMm ?: 0}"
        // The computer with a profile leads; the others are attached as extra records.
        val primaryIndex = entry.computers.indexOfFirst { it.samples.isNotEmpty() }.coerceAtLeast(0)
        val primary = entry.computers.getOrNull(primaryIndex)
        val extras = entry.computers.filterIndexed { i, _ -> i != primaryIndex }
        // Resolve the device from this dive's computer so the same dive logged on two
        // computers merges into one dive that keeps both records.
        val deviceId = importDeviceId(primary?.model, primary?.serial)
        // A raw computer log is kept as is, so it can be re-parsed like a download.
        val incoming = item.parsed?.copy(deviceId = deviceId, number = entry.number ?: item.parsed.number) ?: IncomingDive(
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
            diveMode = primary?.diveMode,
            decoModel = primary?.decoModel,
        )
        val decision = container.dives.classify(incoming)
        // A copy that names no computer adds no profile of its own: when the dive is
        // already logged, it only fills in that dive's gaps.
        val unknownComputer = ComputerNames.split(primary?.model).second == ComputerNames.UNKNOWN && primary?.serial == null
        if (unknownComputer && decision is ImportDecision.MergeCandidate) {
            merger.applyMetadata(decision.diveId, entry)
            return Outcome.SKIPPED
        }
        return when (val result = container.dives.import(incoming, decision) { autoMerge }) {
            is ImportResult.SkippedDuplicate -> {
                // Already logged (e.g. downloaded): still take what this copy adds, such as
                // the site, buddies or tank sizes, without replacing anything.
                container.dives.record(result.existingRecordId)?.let { merger.applyMetadata(it.diveId, entry) }
                Outcome.SKIPPED
            }
            is ImportResult.CreatedDive -> {
                merger.applyMetadata(result.diveId, entry)
                attachExtraComputers(result.diveId, entry, extras, skipKnownDevices = false)
                Outcome.IMPORTED
            }
            is ImportResult.AttachedToDive -> {
                // Fill any metadata the existing dive was missing from this copy.
                merger.applyMetadata(result.diveId, entry)
                attachExtraComputers(result.diveId, entry, extras, skipKnownDevices = true)
                Outcome.MERGED
            }
        }
    }

    /**
     * A dive logged with no computer, such as one entered by hand, is stored without a record
     * so it stays editable as one. A dive already at that time takes only its metadata.
     */
    private fun importWithoutComputer(entry: DiveEntry): Outcome {
        val probe = IncomingDive(
            startEpochSeconds = entry.startEpochSeconds,
            utcOffsetSeconds = entry.utcOffsetSeconds,
            durationSeconds = entry.durationSeconds,
            rawData = ByteArray(0),
            rawFormatId = "",
            fingerprint = "",
        )
        val existing = (container.dives.classify(probe) as? ImportDecision.MergeCandidate)?.diveId
        if (existing != null) {
            merger.applyMetadata(existing, entry)
            return Outcome.SKIPPED
        }
        val diveId = container.dives.insertDive(
            Dive(
                number = entry.number ?: container.dives.nextDiveNumber(),
                startEpochSeconds = entry.startEpochSeconds,
                utcOffsetSeconds = entry.utcOffsetSeconds,
                durationSeconds = entry.durationSeconds,
                maxDepthMm = entry.maxDepthMm,
                meanDepthMm = entry.meanDepthMm,
                waterTempMk = entry.waterTempMk,
                airTempMk = entry.airTempMk,
            ),
        )
        merger.applyMetadata(diveId, entry)
        return Outcome.IMPORTED
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
     * Attach a dive's other computers as extra records. On a merge the dive may already
     * hold one of them (the same dive imported before), which is then left out.
     */
    private fun attachExtraComputers(diveId: Long, entry: DiveEntry, extras: List<ComputerEntry>, skipKnownDevices: Boolean) {
        extras.forEachIndexed { i, computer ->
            val deviceId = importDeviceId(computer.model, computer.serial)
            if (skipKnownDevices && container.dives.hasRecordFromDevice(diveId, deviceId)) return@forEachIndexed
            container.dives.attachToDive(
                IncomingDive(
                    deviceId = deviceId,
                    startEpochSeconds = computer.startEpochSeconds ?: entry.startEpochSeconds,
                    utcOffsetSeconds = entry.utcOffsetSeconds,
                    durationSeconds = computer.durationSeconds ?: entry.durationSeconds,
                    maxDepthMm = computer.maxDepthMm,
                    rawData = ByteArray(0),
                    rawFormatId = "import-extra",
                    fingerprint = "import-extra:$diveId:$i",
                    samples = computer.samples,
                    events = computer.events,
                    diveMode = computer.diveMode,
                    decoModel = computer.decoModel,
                ),
                diveId,
            )
        }
    }

    /** Every site as a file [SiteRef], by site id. */
    private fun siteRefs(): Map<Long, SiteRef> {
        val places = container.sites.allPlaces()
        val countries = container.sites.countries().associateBy { it.id }
        // "Unknown" stands in for a missing country or place; files get none.
        fun real(name: String?) = name?.takeUnless { it.equals(UNKNOWN_PLACE, ignoreCase = true) }
        return container.sites.allSites().associate { site ->
            val place = places[site.placeId]
            val country = place?.let { countries[it.countryId] }
            site.id to SiteRef(
                name = site.name,
                country = real(country?.name),
                place = real(place?.name),
                latitude = site.latitude,
                longitude = site.longitude,
            )
        }
    }

    /**
     * Re-parse every stored dive from its saved raw download, rebuilding samples,
     * events and summaries while keeping user-entered fields (number, notes,
     * rating, site). Lets a parser improvement reach already-imported dives without
     * a re-download. File imports keep no raw data, so they are left untouched.
     * Returns the number of records re-parsed; see [reparseAllWithFailures].
     */
    fun reparseAll(): Int = reparseAllWithFailures().reparsed

    /** [reparseAll], also returning the records whose raw data the parser rejected. */
    fun reparseAllWithFailures(): ReparseResult {
        val records = container.dives.recordsByDive().values.flatten().filter { DiveComputerKind.parserFor(it.rawFormatId) != null }
        var count = 0
        val failures = mutableListOf<ReparseFailure>()
        for (batch in records.chunked(IMPORT_BATCH)) {
            container.dives.inTransaction {
                for (record in batch) {
                    val parser = DiveComputerKind.parserFor(record.rawFormatId) ?: continue
                    val raw = container.dives.rawData(record.id) ?: continue
                    val incoming = runCatching { parser.parse(RawDive(record.fingerprint, raw, record.rawFormatId)) }
                        .getOrElse {
                            failures += ReparseFailure(record.id, it.message ?: it::class.simpleName ?: "error")
                            null
                        } ?: continue
                    container.dives.reparseRecord(record.id, incoming)
                    count++
                }
            }
        }
        return ReparseResult(count, failures)
    }

    companion object {

        fun formats(): List<DiveFormat> = listOf(SubsurfaceXml(), UddfFormat(), MacDiveXml())

        private const val SHEARWATER_CLOUD = "shearwater-cloud-db"

        /** Dives (or records) per transaction on import and re-parse. */
        private const val IMPORT_BATCH = 100

        /** Every export: the text formats, then a Shearwater Cloud database. */
        fun exportTargets(): List<ExportTarget> = exporters().map { it.target }

        private fun exporters(): List<Exporter> =
            formats().map { f ->
                Exporter(ExportTarget(f.id, f.displayName, f.fileExtension, f.mimeType)) { f.write(it).encodeToByteArray() }
            } + Exporter(ExportTarget(SHEARWATER_CLOUD, "Shearwater Cloud database", "db", "application/vnd.sqlite3")) {
                ShearwaterCloudWriter.write(it, Clock.System.now().epochSeconds)
            }

        /** Guess the format from the file content. */
        fun detect(text: String): DiveFormat? = when {
            text.contains("mac-dive") -> MacDiveXml()
            text.contains("<uddf") -> UddfFormat()
            text.contains("<divelog") -> SubsurfaceXml()
            else -> null
        }
    }
}
