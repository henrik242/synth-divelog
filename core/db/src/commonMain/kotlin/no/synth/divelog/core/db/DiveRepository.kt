package no.synth.divelog.core.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.db.sql.SelectDiveSources
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.DiveComputerRecord
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.Tank

/**
 * Dives and the computer records beneath them, including importing downloads and
 * the merge/split operations that let several computers share one dive.
 */
class DiveRepository(private val db: DiveDatabase) {
    private val dives = db.diveQueries
    private val records = db.recordQueries
    private val samples = db.sampleQueries
    private val events = db.eventQueries
    private val deleted = db.deletedRecordQueries
    private val gases = GasRepository(db)

    // --- Dive CRUD ---

    fun nextDiveNumber(): Int =
        ((dives.maxDiveNumber().executeAsOne().maxNumber ?: 0L) + 1).toInt()

    fun insertDive(dive: Dive): Long = db.transactionWithResult {
        dives.insertDive(
            dive.number?.toLong(),
            dive.startEpochSeconds,
            dive.utcOffsetSeconds.toLong(),
            dive.durationSeconds.toLong(),
            dive.maxDepthMm?.toLong(),
            dive.meanDepthMm?.toLong(),
            dive.waterTempMk?.toLong(),
            dive.airTempMk?.toLong(),
            dive.notes,
            dive.rating?.toLong(),
            dive.visibility?.toLong(),
            dive.siteId,
            dive.primaryComputerRecordId,
            dive.visibilityRating?.toLong(),
        )
        dives.lastInsertRowId().executeAsOne()
    }

    fun getDive(id: Long): Dive? = dives.selectDiveById(id).executeAsOneOrNull()?.toDomain()

    fun diveFlow(id: Long): Flow<Dive?> = dives.selectDiveById(id).oneOrNullFlow { it.toDomain() }

    fun allDives(): List<Dive> = dives.selectAllDives().executeAsList().map { it.toDomain() }

    fun allDivesFlow(): Flow<List<Dive>> = dives.selectAllDives().listFlow { it.toDomain() }

    fun divesBySite(siteId: Long): List<Dive> =
        dives.selectDivesBySite(siteId).executeAsList().map { it.toDomain() }

    fun updateDive(dive: Dive) = dives.updateDive(
        dive.number?.toLong(),
        dive.startEpochSeconds,
        dive.utcOffsetSeconds.toLong(),
        dive.durationSeconds.toLong(),
        dive.maxDepthMm?.toLong(),
        dive.meanDepthMm?.toLong(),
        dive.waterTempMk?.toLong(),
        dive.airTempMk?.toLong(),
        dive.notes,
        dive.rating?.toLong(),
        dive.visibility?.toLong(),
        dive.siteId,
        dive.primaryComputerRecordId,
        dive.visibilityRating?.toLong(),
        dive.id,
    )

    /**
     * Delete a dive with its records. Its downloaded records are remembered (see
     * [isDeleted]), so a later download does not bring the dive back unasked.
     */
    fun deleteDive(id: Long) = db.transaction {
        deleted.rememberRecordsOfDive(id)
        dives.deleteDive(id)
    }

    /** Whether the user deleted the dive [deviceId] logged as [fingerprint]. */
    fun isDeleted(deviceId: Long, fingerprint: String): Boolean =
        deleted.isDeleted(deviceId, fingerprint).executeAsOne()

    fun deleteDives(ids: Collection<Long>) = db.transaction { ids.forEach(::deleteDive) }

    fun setSite(diveId: Long, siteId: Long?) = dives.setDiveSite(siteId, diveId)

    /** Run [body] in one transaction; transactions nest, so repository calls inside join it. */
    fun <T> inTransaction(body: () -> T): T = db.transactionWithResult { body() }

    // --- Records, samples, events ---

    fun recordsForDive(diveId: Long): List<DiveComputerRecord> =
        records.selectRecordSummariesForDive(diveId).executeAsList().map { it.toDomain() }

    fun recordsForDiveFlow(diveId: Long): Flow<List<DiveComputerRecord>> =
        records.selectRecordSummariesForDive(diveId).listFlow { it.toDomain() }

    /**
     * Distinct source computers per dive id, for the whole log in one query. A null entry means a
     * record with no device (e.g. a file import). Only the identifying fields of [Device] are filled.
     */
    fun sourcesByDive(): Map<Long, List<Device?>> = groupSources(records.selectDiveSources().executeAsList())

    fun sourcesByDiveFlow(): Flow<Map<Long, List<Device?>>> =
        records.selectDiveSources().listFlow { it }.map(::groupSources)

    private fun groupSources(rows: List<SelectDiveSources>): Map<Long, List<Device?>> =
        rows.groupBy({ it.diveId }) { row ->
            if (row.deviceId == null || row.vendor == null || row.model == null) null
            else Device(
                id = row.deviceId,
                vendor = row.vendor,
                model = row.model,
                serial = row.serial,
                nickname = row.nickname,
            )
        }

    /** Every record's summary by dive id, in start order, in one query. */
    fun recordsByDive(): Map<Long, List<DiveComputerRecord>> =
        records.selectAllRecordSummaries().executeAsList().groupBy({ it.diveId }) { it.toDomain() }

    /** Whether [diveId] already has a record from [deviceId]. */
    fun hasRecordFromDevice(diveId: Long, deviceId: Long): Boolean =
        records.selectRecordFromDeviceAmongDives(deviceId, listOf(diveId)).executeAsOneOrNull() != null

    fun record(id: Long): DiveComputerRecord? =
        records.selectRecordSummaryById(id).executeAsOneOrNull()?.toDomain()

    /** The untouched download, kept so a record can be re-parsed later. */
    fun rawData(recordId: Long): ByteArray? =
        records.selectRawData(recordId).executeAsOneOrNull()

    fun samplesForRecord(recordId: Long): List<Sample> {
        val pressuresByTime: Map<Long, Map<Int, Int>> =
            samples.selectTankPressuresForRecord(recordId).executeAsList()
                .groupBy { it.timeOffsetSeconds }
                .mapValues { (_, rows) ->
                    rows.associate { it.tankIndex.toInt() to it.pressureMbar.toInt() }
                }
        return samples.selectSamplesForRecord(recordId).executeAsList().map { row ->
            row.toDomain(pressuresByTime[row.timeOffsetSeconds] ?: emptyMap())
        }
    }

    fun eventsForRecord(recordId: Long): List<Event> =
        events.selectEventsForRecord(recordId).executeAsList().map { it.toDomain() }

    /** Every record's samples by record id, in two queries. */
    fun samplesByRecord(): Map<Long, List<Sample>> {
        val pressures: Map<Pair<Long, Long>, Map<Int, Int>> = samples.selectAllTankPressures().executeAsList()
            .groupBy { it.recordId to it.timeOffsetSeconds }
            .mapValues { (_, rows) -> rows.associate { it.tankIndex.toInt() to it.pressureMbar.toInt() } }
        return samples.selectAllSamples().executeAsList().groupBy({ it.recordId }) { row ->
            row.toDomain(pressures[row.recordId to row.timeOffsetSeconds] ?: emptyMap())
        }
    }

    /** Every record's events by record id, in one query. */
    fun eventsByRecord(): Map<Long, List<Event>> =
        events.selectAllEvents().executeAsList().groupBy({ it.recordId }) { it.toDomain() }

    // --- Import ---

    /**
     * Fingerprint of the newest dive already stored or deleted for [deviceId], or null if
     * none. Passed to a protocol download so it stops once it reaches dives already here.
     */
    fun newestFingerprint(deviceId: Long): String? =
        records.selectNewestFingerprintForDevice(deviceId).executeAsOneOrNull()

    /** Decide what to do with an incoming record without writing anything. */
    fun classify(incoming: IncomingDive): ImportDecision {
        val duplicate = incoming.deviceId?.let { deviceId ->
            records.findByDeviceAndFingerprint(deviceId, incoming.fingerprint).executeAsOneOrNull()
        }
        if (duplicate != null) return ImportDecision.Duplicate(duplicate.id)

        // A dive without a duration still falls inside a dive that spans its start.
        val start = incoming.startEpochSeconds + incoming.utcOffsetSeconds
        val end = start + maxOf(incoming.durationSeconds, 1)
        val overlaps = dives.selectOverlappingDives(end, start)
            .executeAsList()
        // One computer cannot log two dives at once: an overlapping dive that already has a
        // record from this computer is this dive, e.g. from another logbook of the same diver.
        val sameComputer = incoming.deviceId?.takeIf { overlaps.isNotEmpty() }?.let { deviceId ->
            records.selectRecordFromDeviceAmongDives(deviceId, overlaps.map { it.id }).executeAsOneOrNull()
        }
        if (sameComputer != null) return ImportDecision.Duplicate(sameComputer.id)

        val overlap = overlaps.firstOrNull()
        return if (overlap != null) ImportDecision.MergeCandidate(overlap.id) else ImportDecision.NewDive
    }

    /** Create a fresh dive from the incoming record; its summary becomes the dive's. */
    fun importAsNewDive(incoming: IncomingDive): ImportResult.CreatedDive = db.transactionWithResult {
        dives.insertDive(
            (incoming.number ?: nextDiveNumber()).toLong(),
            incoming.startEpochSeconds,
            incoming.utcOffsetSeconds.toLong(),
            incoming.durationSeconds.toLong(),
            incoming.maxDepthMm?.toLong(),
            incoming.meanDepthMm?.toLong(),
            incoming.waterTempMk?.toLong(),
            incoming.airTempMk?.toLong(),
            null,
            null,
            null,
            null,
            null,
            null,
        )
        val diveId = dives.lastInsertRowId().executeAsOne()
        val recordId = insertRecord(incoming, diveId, incoming.utcOffsetSeconds)
        dives.setPrimaryRecord(recordId, diveId)
        addMissingGases(diveId, incoming.gases)
        ImportResult.CreatedDive(diveId, recordId)
    }

    /** Attach the incoming record to an existing dive as an extra computer. */
    fun attachToDive(incoming: IncomingDive, diveId: Long): ImportResult.AttachedToDive =
        db.transactionWithResult {
            val diveOffset = dives.selectDiveById(diveId).executeAsOne().utcOffsetSeconds.toInt()
            val recordId = insertRecord(incoming, diveId, diveOffset)
            addMissingGases(diveId, incoming.gases)
            ImportResult.AttachedToDive(diveId, recordId)
        }

    /**
     * Full import flow. Duplicates are skipped; an overlap is attached only when
     * [confirmMerge] approves the candidate dive; otherwise a new dive is made.
     */
    fun import(incoming: IncomingDive, confirmMerge: (candidateDiveId: Long) -> Boolean): ImportResult =
        import(incoming, classify(incoming), confirmMerge)

    /** [import] with the [decision] already made by [classify]. */
    fun import(
        incoming: IncomingDive,
        decision: ImportDecision,
        confirmMerge: (candidateDiveId: Long) -> Boolean,
    ): ImportResult =
        when (decision) {
            is ImportDecision.Duplicate -> ImportResult.SkippedDuplicate(decision.existingRecordId)
            is ImportDecision.MergeCandidate ->
                if (confirmMerge(decision.diveId)) attachToDive(incoming, decision.diveId)
                else importAsNewDive(incoming)
            ImportDecision.NewDive -> importAsNewDive(incoming)
        }

    /**
     * Give [diveId] a tank for each of [mixes] it does not have yet, after its existing
     * tanks. Tanks the user or a logbook set up are left as they are.
     */
    private fun addMissingGases(diveId: Long, mixes: List<GasMix>) {
        if (mixes.isEmpty()) return
        val tanks = gases.tanksForDive(diveId)
        val have = tanks.mapNotNull { t -> t.gasMixId?.let { gases.gasMix(it) } }
            .map { it.o2Permille to it.hePermille }.toMutableSet()
        var index = (tanks.maxOfOrNull { it.index } ?: -1) + 1
        for (mix in mixes) {
            if (!have.add(mix.o2Permille to mix.hePermille)) continue
            val mixId = gases.getOrCreateGasMix(mix.o2Permille, mix.hePermille)
            gases.addTank(Tank(diveId = diveId, index = index++, gasMixId = mixId))
        }
    }

    /**
     * [incoming]'s start in the time frame of a dive with offset [diveOffset]: the same wall
     * clock. A record stores its start in its dive's frame, whichever frame its source used.
     */
    private fun startInDiveFrame(incoming: IncomingDive, diveOffset: Int): Long =
        incoming.startEpochSeconds + incoming.utcOffsetSeconds - diveOffset

    private fun insertRecord(incoming: IncomingDive, diveId: Long, diveOffset: Int): Long {
        records.insertRecord(
            diveId,
            incoming.deviceId,
            startInDiveFrame(incoming, diveOffset),
            incoming.durationSeconds.toLong(),
            incoming.maxDepthMm?.toLong(),
            incoming.rawData,
            incoming.rawFormatId,
            incoming.fingerprint,
            incoming.diveMode?.name,
            incoming.decoModel?.algorithm?.name,
            incoming.decoModel?.gfLowPercent?.toLong(),
            incoming.decoModel?.gfHighPercent?.toLong(),
            incoming.decoModel?.conservatism?.toLong(),
        )
        val recordId = records.lastInsertRowId().executeAsOne()
        insertSamplesAndEvents(recordId, incoming)
        incoming.deviceId?.let { deleted.forget(it, incoming.fingerprint) }
        return recordId
    }

    private fun insertSamplesAndEvents(recordId: Long, incoming: IncomingDive) {
        // A sample is keyed on (recordId, timeOffsetSeconds); some sources (e.g. MacDive)
        // emit more than one sample at the same time, so keep the first per time.
        for (s in incoming.samples.distinctBy { it.timeOffsetSeconds }) {
            samples.insertSample(
                recordId,
                s.timeOffsetSeconds.toLong(),
                s.depthMm?.toLong(),
                s.temperatureMk?.toLong(),
                s.ppO2Mbar?.toLong(),
                s.ndlSeconds?.toLong(),
                s.ceilingMm?.toLong(),
                s.stopDepthMm?.toLong(),
                s.stopTimeSeconds?.toLong(),
                s.cnsPermille?.toLong(),
                s.activeGasIndex?.toLong(),
                s.ttsSeconds?.toLong(),
            )
            for ((tankIndex, mbar) in s.tankPressuresMbar) {
                samples.insertSampleTankPressure(
                    recordId,
                    s.timeOffsetSeconds.toLong(),
                    tankIndex.toLong(),
                    mbar.toLong(),
                )
            }
        }
        for (e in incoming.events) {
            events.insertEvent(recordId, e.timeOffsetSeconds.toLong(), e.type.name, e.value)
        }
    }

    /**
     * Re-apply a parser to a record's stored raw data: replace its samples and
     * events and refresh its summary, and the dive's summary if this is the dive's
     * primary record. User-entered fields (number, notes, rating, site) are kept.
     * Lets a parser fix reach already-imported dives without a re-download.
     */
    fun reparseRecord(recordId: Long, incoming: IncomingDive) = db.transaction {
        val rec = records.selectRecordSummaryById(recordId).executeAsOne()
        val dive = dives.selectDiveById(rec.diveId).executeAsOne().toDomain()
        val start = startInDiveFrame(incoming, dive.utcOffsetSeconds)
        val tankIndices = storedTankIndices(recordId, incoming)
        samples.deleteSamplesForRecord(recordId)
        samples.deleteTankPressuresForRecord(recordId)
        events.deleteEventsForRecord(recordId)
        records.updateRecordSummary(
            start,
            incoming.durationSeconds.toLong(),
            incoming.maxDepthMm?.toLong(),
            incoming.diveMode?.name,
            incoming.decoModel?.algorithm?.name,
            incoming.decoModel?.gfLowPercent?.toLong(),
            incoming.decoModel?.gfHighPercent?.toLong(),
            incoming.decoModel?.conservatism?.toLong(),
            recordId,
        )
        insertSamplesAndEvents(recordId, incoming)
        renumberTanks(recordId, tankIndices)

        addMissingGases(rec.diveId, incoming.gases)
        if (dive.primaryComputerRecordId == recordId) {
            // Other records (more computers, or dives merged into this one) keep their share
            // of the summary: it spans every record and takes the deepest.
            val others = records.selectRecordSummariesForDive(rec.diveId).executeAsList().filter { it.id != recordId }
            updateDive(
                dive.copy(
                    startEpochSeconds = start,
                    durationSeconds = (others.maxOfOrNull { it.startEpochSeconds + it.durationSeconds } ?: 0L)
                        .let { end -> maxOf(incoming.durationSeconds.toLong(), end - start).toInt() },
                    maxDepthMm = (others.mapNotNull { it.maxDepthMm?.toInt() } + listOfNotNull(incoming.maxDepthMm)).maxOrNull(),
                    meanDepthMm = if (others.isEmpty()) incoming.meanDepthMm else dive.meanDepthMm,
                    waterTempMk = incoming.waterTempMk,
                ),
            )
        }
    }

    /**
     * The tank index [recordId] stores for each tank index of [incoming], matched by equal
     * pressures at the same times, so a re-parse keeps the tanks a merge moved its pressures
     * to. A tank with no match keeps its index.
     */
    private fun storedTankIndices(recordId: Long, incoming: IncomingDive): Map<Int, Int> {
        val stored = samples.selectTankPressuresForRecord(recordId).executeAsList()
            .groupBy({ it.tankIndex.toInt() }) { it.timeOffsetSeconds to it.pressureMbar }
            .mapValues { (_, points) -> points.toSet() }
        if (stored.isEmpty()) return emptyMap()
        val fresh = HashMap<Int, MutableSet<Pair<Long, Long>>>()
        for (s in incoming.samples) {
            for ((tank, mbar) in s.tankPressuresMbar) {
                fresh.getOrPut(tank) { HashSet() }.add(s.timeOffsetSeconds.toLong() to mbar.toLong())
            }
        }
        return fresh.mapNotNull { (tank, points) ->
            stored.entries
                .map { (index, old) -> index to old.count { it in points } }
                .filter { (_, matches) -> matches > 0 }
                .maxByOrNull { (_, matches) -> matches }
                ?.let { (index, _) -> tank to index }
        }.toMap()
    }

    /**
     * Replace a stored record with a fresh download of it: its raw data and fingerprint,
     * then [reparseRecord]. The dive's user-entered fields are kept.
     */
    fun refreshRecord(recordId: Long, incoming: IncomingDive) = db.transaction {
        records.updateRecordRaw(incoming.rawData, incoming.rawFormatId, incoming.fingerprint, recordId)
        reparseRecord(recordId, incoming)
    }

    // --- Manual merge and split ---

    /** Move a record onto its own new dive, copying its summary. The record keeps its frame. */
    fun splitRecordIntoNewDive(recordId: Long): Long = db.transactionWithResult {
        val rec = records.selectRecordSummaryById(recordId).executeAsOne()
        val sourceDive = dives.selectDiveById(rec.diveId).executeAsOne()
        val recordCount = records.countRecordsForDive(rec.diveId).executeAsOne()
        require(recordCount > 1) { "Cannot split the only record off a dive" }

        dives.insertDive(
            nextDiveNumber().toLong(),
            rec.startEpochSeconds,
            sourceDive.utcOffsetSeconds,
            rec.durationSeconds,
            rec.maxDepthMm,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )
        val newDiveId = dives.lastInsertRowId().executeAsOne()
        records.reassignRecordToDive(newDiveId, recordId)
        dives.setPrimaryRecord(recordId, newDiveId)

        if (sourceDive.primaryComputerRecordId == recordId) {
            val remaining = records.selectRecordSummariesForDive(rec.diveId).executeAsList()
            dives.setPrimaryRecord(remaining.first().id, rec.diveId)
        }
        newDiveId
    }

    /**
     * Fold [sourceDiveId] into [targetDiveId], then drop the source: its records (moved into
     * the target's time frame), buddies, tags and tanks (merged by gas, see
     * [GasRepository.mergeTanks]) move over, and the target's empty fields take the
     * source's values. The target keeps its primary record and start.
     */
    fun mergeDives(sourceDiveId: Long, targetDiveId: Long) {
        require(sourceDiveId != targetDiveId) { "Cannot merge a dive into itself" }
        db.transaction {
            val source = dives.selectDiveById(sourceDiveId).executeAsOne().toDomain()
            val target = dives.selectDiveById(targetDiveId).executeAsOne().toDomain()
            // Tanks first: their indices on the target decide where the pressure samples go.
            val tankIndices = gases.mergeTanks(targetDiveId, gases.tanksForDive(sourceDiveId))
            records.selectRecordSummariesForDive(sourceDiveId).executeAsList().forEach { renumberTanks(it.id, tankIndices) }
            records.moveRecordsToDive(
                targetDiveId = targetDiveId,
                shiftSeconds = (source.utcOffsetSeconds - target.utcOffsetSeconds).toLong(),
                sourceDiveId = sourceDiveId,
            )
            db.peopleQueries.copyDiveBuddies(targetDiveId = targetDiveId, sourceDiveId = sourceDiveId)
            db.tagsQueries.copyDiveTags(targetDiveId = targetDiveId, sourceDiveId = sourceDiveId)
            val filled = target.copy(
                number = target.number ?: source.number,
                maxDepthMm = target.maxDepthMm ?: source.maxDepthMm,
                meanDepthMm = target.meanDepthMm ?: source.meanDepthMm,
                waterTempMk = target.waterTempMk ?: source.waterTempMk,
                airTempMk = target.airTempMk ?: source.airTempMk,
                notes = target.notes?.takeIf { it.isNotBlank() } ?: source.notes,
                rating = target.rating ?: source.rating,
                visibility = target.visibility ?: source.visibility,
                visibilityRating = target.visibilityRating ?: source.visibilityRating,
                siteId = target.siteId ?: source.siteId,
            )
            if (filled != target) updateDive(filled)
            dives.deleteDive(sourceDiveId)
        }
    }

    /** Move [recordId]'s tank pressure samples to the tank indices in [indices] (old to new). */
    private fun renumberTanks(recordId: Long, indices: Map<Int, Int>) {
        if (indices.all { (from, to) -> from == to }) return
        val rows = samples.selectTankPressuresForRecord(recordId).executeAsList()
        if (rows.none { indices[it.tankIndex.toInt()]?.let { to -> to.toLong() != it.tankIndex } == true }) return
        samples.deleteTankPressuresForRecord(recordId)
        rows.map { it.copy(tankIndex = indices[it.tankIndex.toInt()]?.toLong() ?: it.tankIndex) }
            .distinctBy { it.timeOffsetSeconds to it.tankIndex }
            .forEach { samples.insertSampleTankPressure(recordId, it.timeOffsetSeconds, it.tankIndex, it.pressureMbar) }
    }

    /**
     * Whether [diveIds] can be merged into one: at least two, and no computer logged two
     * of them at the same time (one computer cannot log two dives at once).
     */
    fun canMerge(diveIds: Collection<Long>): Boolean {
        val ids = diveIds.toSet()
        if (ids.size < 2) return false
        val spans = ids.flatMap { id ->
            val offset = dives.selectDiveById(id).executeAsOneOrNull()?.utcOffsetSeconds ?: return false
            records.selectRecordSummariesForDive(id).executeAsList().mapNotNull { r ->
                r.deviceId?.let { RecordSpan(id, it, r.startEpochSeconds + offset, r.startEpochSeconds + offset + r.durationSeconds) }
            }
        }
        return spans.groupBy { it.deviceId }.values.none { same ->
            same.any { a -> same.any { b -> a.diveId != b.diveId && a.start < b.end && b.start < a.end } }
        }
    }

    private data class RecordSpan(val diveId: Long, val deviceId: Long, val start: Long, val end: Long)

    /**
     * Merge [diveIds] into the earliest of them (see [mergeDives] for what moves over) and
     * return its id. Computers that logged the same time keep one record each, a
     * multi-computer dive. The records of one computer from dives one after another are
     * joined end to end into one record (see [concatenateRecords]), so dives split by a
     * short surface interval become one single-computer dive. Fails unless [canMerge].
     */
    fun mergeDives(diveIds: Collection<Long>): Long = db.transactionWithResult {
        require(canMerge(diveIds)) { "One computer logged two of these dives at once" }
        val ordered = diveIds.toSet()
            .map { dives.selectDiveById(it).executeAsOne().toDomain() }
            .sortedBy { it.startEpochSeconds + it.utcOffsetSeconds }
        val target = ordered.first()
        ordered.drop(1).forEach { mergeDives(sourceDiveId = it.id, targetDiveId = target.id) }

        val joined = recordsForDive(target.id)
            .filter { it.deviceId != null }
            .groupBy { it.deviceId }
            .values
            .filter { it.size > 1 }
            .mapNotNull { group -> concatenateRecords(group)?.let { kept -> group to kept } }
        val merged = requireNotNull(getDive(target.id)) { "Merged dive is gone" }
        // A joined primary record gives way to the record it was joined into.
        val primary = joined.firstOrNull { (group, _) -> group.any { it.id == merged.primaryComputerRecordId } }
            ?.second ?: merged.primaryComputerRecordId
        // The summary spans every merged dive, in the kept dive's time frame. Mean depth
        // is over the dives' own times, weighted by duration.
        val end = ordered.maxOf { it.startEpochSeconds + it.utcOffsetSeconds - target.utcOffsetSeconds + it.durationSeconds }
        val weighted = ordered.mapNotNull { d -> d.meanDepthMm?.takeIf { d.durationSeconds > 0 }?.let { it to d.durationSeconds } }
        val meanDepth = if (weighted.isEmpty()) merged.meanDepthMm
        else (weighted.sumOf { (mean, s) -> mean.toLong() * s } / weighted.sumOf { (_, s) -> s.toLong() }).toInt()
        val summarised = merged.copy(
            durationSeconds = maxOf(merged.durationSeconds.toLong(), end - merged.startEpochSeconds).toInt(),
            maxDepthMm = ordered.mapNotNull { it.maxDepthMm }.maxOrNull(),
            meanDepthMm = meanDepth,
            primaryComputerRecordId = primary,
        )
        if (summarised != merged) updateDive(summarised)
        target.id
    }

    /**
     * Join one computer's [parts] (records of one dive, one after another) into the first:
     * the later parts' samples and events move onto its timeline and the parts go. The
     * surface interval between two parts is logged at the surface. The joined record's raw
     * download covers only the first part, so it is no longer re-parsed
     * ([CONCATENATED_FORMAT_ID]); it takes the last part's fingerprint, so a later download
     * stops at the right dive. Returns the kept record, or null (nothing changed) when the
     * parts overlap.
     */
    private fun concatenateRecords(parts: List<DiveComputerRecord>): Long? {
        val ordered = parts.sortedBy { it.startEpochSeconds }
        if (ordered.zipWithNext().any { (a, b) -> a.endEpochSeconds > b.startEpochSeconds }) return null
        val first = ordered.first()
        var end = first.endEpochSeconds
        // The kept record takes the last part's fingerprint; remember the others', which go.
        ordered.dropLast(1).forEach { deleted.rememberRecord(it.id) }
        for (part in ordered.drop(1)) {
            val shift = part.startEpochSeconds - first.startEpochSeconds
            val times = samples.selectSamplesForRecord(first.id).executeAsList().mapTo(HashSet()) { it.timeOffsetSeconds }
            if (times.isNotEmpty() && samples.countSamplesForRecord(part.id).executeAsOne() > 0) {
                // At the surface from the end of one part (or a second later, when the part
                // logged its end) until a second before the next. No stop and no TTS are 0,
                // not null, or the last values carry over the interval after an export.
                val surfaceFrom = end - first.startEpochSeconds
                val surface = listOf(if (surfaceFrom in times) surfaceFrom + 1 else surfaceFrom, shift - 1).distinct()
                for (t in surface) {
                    if (t in surfaceFrom until shift && t !in times) {
                        samples.insertSample(first.id, t, 0, null, null, null, 0, 0, 0, null, null, 0)
                    }
                }
            }
            samples.moveSamplesToRecord(targetRecordId = first.id, shiftSeconds = shift, sourceRecordId = part.id)
            samples.moveTankPressuresToRecord(targetRecordId = first.id, shiftSeconds = shift, sourceRecordId = part.id)
            events.moveEventsToRecord(targetRecordId = first.id, shiftSeconds = shift, sourceRecordId = part.id)
            records.deleteRecord(part.id)
            end = maxOf(end, part.endEpochSeconds)
        }
        records.updateConcatenatedRecord(
            durationSeconds = end - first.startEpochSeconds,
            maxDepthMm = ordered.mapNotNull { it.maxDepthMm }.maxOrNull()?.toLong(),
            rawData = ByteArray(0),
            rawFormatId = CONCATENATED_FORMAT_ID,
            fingerprint = ordered.last().fingerprint,
            id = first.id,
        )
        return first.id
    }

    companion object {
        /** Format of a record joined from several downloads; no parser reads it. */
        const val CONCATENATED_FORMAT_ID = "concatenated"
    }
}
