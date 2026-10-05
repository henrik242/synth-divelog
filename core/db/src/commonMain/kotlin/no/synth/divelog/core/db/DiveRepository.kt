package no.synth.divelog.core.db

import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.DiveComputerRecord
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample

/**
 * Dives and the computer records beneath them, including importing downloads and
 * the merge/split operations that let several computers share one dive.
 */
class DiveRepository(private val db: DiveDatabase) {
    private val dives = db.diveQueries
    private val records = db.recordQueries
    private val samples = db.sampleQueries
    private val events = db.eventQueries

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
        )
        dives.lastInsertRowId().executeAsOne()
    }

    fun getDive(id: Long): Dive? = dives.selectDiveById(id).executeAsOneOrNull()?.toDomain()

    fun allDives(): List<Dive> = dives.selectAllDives().executeAsList().map { it.toDomain() }

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
        dive.id,
    )

    fun deleteDive(id: Long) = dives.deleteDive(id)

    fun setSite(diveId: Long, siteId: Long?) = dives.setDiveSite(siteId, diveId)

    // --- Records, samples, events ---

    fun recordsForDive(diveId: Long): List<DiveComputerRecord> =
        records.selectRecordSummariesForDive(diveId).executeAsList().map { it.toDomain() }

    /**
     * Distinct source computers per dive id, for the whole log in one query. A null entry means a
     * record with no device (e.g. a file import). Only the identifying fields of [Device] are filled.
     */
    fun sourcesByDive(): Map<Long, List<Device?>> =
        records.selectDiveSources().executeAsList().groupBy({ it.diveId }) { row ->
            if (row.deviceId == null || row.vendor == null || row.model == null) null
            else Device(
                id = row.deviceId,
                vendor = row.vendor,
                model = row.model,
                serial = row.serial,
                nickname = row.nickname,
            )
        }

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

    // --- Import ---

    /**
     * Fingerprint of the newest dive already stored for [deviceId], or null if none.
     * Passed to a protocol download so it stops once it reaches dives already here.
     */
    fun newestFingerprint(deviceId: Long): String? =
        records.selectNewestFingerprintForDevice(deviceId).executeAsOneOrNull()

    /** Decide what to do with an incoming record without writing anything. */
    fun classify(incoming: IncomingDive): ImportDecision {
        val duplicate = incoming.deviceId?.let { deviceId ->
            records.findByDeviceAndFingerprint(deviceId, incoming.fingerprint).executeAsOneOrNull()
        }
        if (duplicate != null) return ImportDecision.Duplicate(duplicate.id)

        val overlap = dives.selectOverlappingDives(incoming.endEpochSeconds, incoming.startEpochSeconds)
            .executeAsList()
            .firstOrNull()
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
        )
        val diveId = dives.lastInsertRowId().executeAsOne()
        val recordId = insertRecord(incoming, diveId)
        dives.setPrimaryRecord(recordId, diveId)
        ImportResult.CreatedDive(diveId, recordId)
    }

    /** Attach the incoming record to an existing dive as an extra computer. */
    fun attachToDive(incoming: IncomingDive, diveId: Long): ImportResult.AttachedToDive =
        db.transactionWithResult {
            val recordId = insertRecord(incoming, diveId)
            ImportResult.AttachedToDive(diveId, recordId)
        }

    /**
     * Full import flow. Duplicates are skipped; an overlap is attached only when
     * [confirmMerge] approves the candidate dive; otherwise a new dive is made.
     */
    fun import(incoming: IncomingDive, confirmMerge: (candidateDiveId: Long) -> Boolean): ImportResult =
        when (val decision = classify(incoming)) {
            is ImportDecision.Duplicate -> ImportResult.SkippedDuplicate(decision.existingRecordId)
            is ImportDecision.MergeCandidate ->
                if (confirmMerge(decision.diveId)) attachToDive(incoming, decision.diveId)
                else importAsNewDive(incoming)
            ImportDecision.NewDive -> importAsNewDive(incoming)
        }

    private fun insertRecord(incoming: IncomingDive, diveId: Long): Long {
        records.insertRecord(
            diveId,
            incoming.deviceId,
            incoming.startEpochSeconds,
            incoming.durationSeconds.toLong(),
            incoming.maxDepthMm?.toLong(),
            incoming.rawData,
            incoming.rawFormatId,
            incoming.fingerprint,
        )
        val recordId = records.lastInsertRowId().executeAsOne()
        insertSamplesAndEvents(recordId, incoming)
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
        samples.deleteSamplesForRecord(recordId)
        samples.deleteTankPressuresForRecord(recordId)
        events.deleteEventsForRecord(recordId)
        records.updateRecordSummary(
            incoming.startEpochSeconds,
            incoming.durationSeconds.toLong(),
            incoming.maxDepthMm?.toLong(),
            recordId,
        )
        insertSamplesAndEvents(recordId, incoming)

        val rec = records.selectRecordSummaryById(recordId).executeAsOne()
        val dive = dives.selectDiveById(rec.diveId).executeAsOneOrNull()?.toDomain()
        if (dive != null && dive.primaryComputerRecordId == recordId) {
            updateDive(
                dive.copy(
                    startEpochSeconds = incoming.startEpochSeconds,
                    durationSeconds = incoming.durationSeconds,
                    maxDepthMm = incoming.maxDepthMm,
                    meanDepthMm = incoming.meanDepthMm,
                    waterTempMk = incoming.waterTempMk,
                ),
            )
        }
    }

    // --- Manual merge and split ---

    /** Move a record onto its own new dive, copying its summary. */
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

    /** Fold all of [sourceDiveId]'s records and buddies into [targetDiveId], then drop the source. */
    fun mergeDives(sourceDiveId: Long, targetDiveId: Long) {
        require(sourceDiveId != targetDiveId) { "Cannot merge a dive into itself" }
        db.transaction {
            records.selectRecordSummariesForDive(sourceDiveId).executeAsList().forEach {
                records.reassignRecordToDive(targetDiveId, it.id)
            }
            val people = db.peopleQueries
            people.selectBuddiesForDive(sourceDiveId).executeAsList().forEach {
                people.linkDiveBuddy(targetDiveId, it.id)
            }
            dives.deleteDive(sourceDiveId)
        }
    }
}
