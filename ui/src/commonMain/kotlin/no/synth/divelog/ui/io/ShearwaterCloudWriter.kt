package no.synth.divelog.ui.io

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.TankEntry
import no.synth.divelog.core.formats.UddfFormat
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.ui.format.Format
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Writes a logbook as a Shearwater Cloud database, for its "Import Database". Every dive
 * goes in the way Shearwater Cloud keeps a dive it imported from another logbook: the dive
 * as a UDDF document in `log_data`, its logbook fields (site, buddies, notes, tanks,
 * serial) in `dive_details`, and a sync record. Tables and version rows are Shearwater's
 * own ([ShearwaterCloudSchema]). A dive logged by two computers becomes two dives, as
 * Shearwater Cloud keeps them; importing the file back merges them again.
 */
internal object ShearwaterCloudWriter {
    fun write(log: DiveLog, nowEpochSeconds: Long): ByteArray = withSqliteFile(null) { db ->
        val now = stamp(nowEpochSeconds)
        db.transaction {
            ShearwaterCloudSchema.TABLES.forEach { db.execute(it) }
            for ((id, version, updated) in ShearwaterCloudSchema.VERSIONS) {
                db.execute("INSERT INTO SWC_TableVersion (Id, DbVersion, Updated) VALUES (?, ?, ?)", listOf(id, version, updated))
            }
            perComputer(log).forEachIndexed { i, dive -> writeDive(db, diveId(i, dive), dive, nowEpochSeconds, now) }
        }
        // Shearwater Cloud's own exports leave the user version at 0.
        db.execute("PRAGMA user_version = 0")
    }.second

    /** One dive per computer that recorded a profile; a dive without one stays as it is. */
    private fun perComputer(log: DiveLog): List<DiveEntry> = log.dives.flatMap { dive ->
        val recorded = dive.computers.filter { it.samples.isNotEmpty() }
        if (recorded.size < 2) listOf(dive) else recorded.map { dive.copy(computers = listOf(it)) }
    }

    private fun writeDive(db: SqliteFile, id: String, dive: DiveEntry, nowEpochSeconds: Long, now: String) {
        val stats = Stats.of(dive)
        val uddf = UddfFormat().write(DiveLog(listOf(dive))).encodeToByteArray()
        db.execute(
            "INSERT INTO log_data (log_id, table_version, created_unixtime, modified_unixtime, file_name, format, " +
                "format_version, calculated_values_from_samples, data_bytes_1) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(id, TABLE_VERSION, nowEpochSeconds, nowEpochSeconds, "synth-divelog.uddf", "uddf", 0L, calculated(stats).toString(), ShearwaterCloudQueries.store(uddf)),
        )

        val tanks = dive.tanks.sortedBy { it.index }
        val local = dive.startEpochSeconds + dive.utcOffsetSeconds
        val fields = linkedMapOf<String, Any?>(
            "DiveId" to id,
            "DataVersion" to TABLE_VERSION,
            "LastModified" to now,
            "DiveDate" to stamp(local),
            "DateAndTime" to usStamp(local),
            "Depth" to dive.maxDepthMm?.let { Format.oneDecimal(it / 1000.0) },
            "SerialNumber" to shearwaterSerial(dive),
            // Shearwater Cloud never leaves these empty; 0 is its "no data".
            "AverageDepth" to (stats.meanDepthM ?: 0.0),
            "AverageTemp" to (stats.meanTempC ?: 0.0),
            "MinTemp" to (stats.minTempC ?: 0.0),
            "MaxTemp" to (stats.maxTempC ?: 0.0),
            "EndGF99" to 0.0,
            "Location" to dive.site?.let { it.place ?: it.country },
            "Site" to dive.site?.name,
            "Buddy" to dive.buddies.joinToString(", ").ifEmpty { null },
            "DiveNumber" to dive.number?.toString(),
            "TankProfileData" to tankProfile(tanks, dive, stats).toString(),
            "TankSize" to tanks.mapNotNull { t -> t.volumeMl?.let { (it / 1_000_000.0).toString() } }.joinToString(", ").ifEmpty { null },
            "Notes" to dive.notes,
        )
        tanks.take(4).forEachIndexed { i, t ->
            fields["Tank${i + 1}PressureStart"] = t.startPressureMbar?.let { psi(it).toString() }
            fields["Tank${i + 1}PressureEnd"] = t.endPressureMbar?.let { psi(it).toString() }
        }
        val written = fields.filterValues { it != null }
        db.execute(
            "INSERT INTO dive_details (${written.keys.joinToString(", ")}) VALUES (${written.keys.joinToString(", ") { "?" }})",
            written.values.toList(),
        )

        // When each field was last set: now for the ones written, "never" for the rest.
        val nowMs = nowEpochSeconds * 1000
        val timestamps = JsonObject(SYNCED_FIELDS.associateWith { JsonPrimitive(if (it in written) nowMs else NEVER_MS) })
        db.execute(
            "INSERT INTO SyncV3MetadataDiveDetail (Id, LastModifiedDevice, LastModifiedServerTime, CreatedDevice, CreatedTime, " +
                "FieldTimeStampJson, Version) VALUES (?, ?, ?, ?, ?, ?, ?)",
            listOf(id, DEVICE_ID, now, DEVICE_ID, now, timestamps.toString(), 1L),
        )
    }

    /** Six open-circuit tank slots, the first four with a transmitter slot, as Shearwater Cloud keeps them. */
    private fun tankProfile(tanks: List<TankEntry>, dive: DiveEntry, stats: Stats): JsonObject {
        fun profile(index: Int, t: TankEntry?) = obj(
            "profileIndex" to index,
            "O2Percent" to ((t?.o2Permille ?: 0) / 10),
            "HePercent" to ((t?.hePermille ?: 0) / 10),
            "CircuitMode" to if (t?.o2Permille != null) OPEN_CIRCUIT else 0,
            "CircuitSwitchType" to 0,
            "StartTimeInSeconds" to 0.0,
            "EndTimeInSeconds" to if (t?.o2Permille != null) dive.durationSeconds.toDouble() else 0.0,
            "AverageDepthInMeters" to if (t?.o2Permille != null) (stats.meanDepthM ?: 0.0) else 0.0,
        )
        fun slot(index: Int, t: TankEntry?) = obj(
            "StartPressurePSI" to (t?.startPressureMbar?.let { psi(it).toString() } ?: ""),
            "EndPressurePSI" to (t?.endPressureMbar?.let { psi(it).toString() } ?: ""),
            "GasProfile" to profile(index, t),
            "DiveTransmitter" to if (index < 4) {
                obj(
                    "TankIndex" to index, "IsOn" to false, "UnformattedSerialNumber" to null,
                    "Name" to null, "DefaultScriptTerm" to "dive_details/tank_${index + 1}",
                )
            } else {
                null
            },
            "SurfacePressureMBar" to SURFACE_MBAR,
            "Salinity" to SALINITY,
        )
        val gases = tanks.filter { it.o2Permille != null }.distinctBy { it.o2Permille to it.hePermille }
        return obj(
            "GasProfiles" to JsonArray(gases.mapIndexed { i, t -> profile(i, t) }),
            "TankData" to JsonArray((0 until TANK_SLOTS).map { slot(it, tanks.getOrNull(it)) }),
            "CcrTankData" to JsonArray((0 until TANK_SLOTS).map { slot(it, null) }),
        )
    }

    private fun calculated(s: Stats) = obj(
        "AverageDepth" to (s.meanDepthM ?: 0.0),
        "AverageTemp" to (s.meanTempC ?: 0.0),
        "MinTemp" to (s.minTempC ?: 0.0),
        "MaxTemp" to (s.maxTempC ?: 0.0),
        "EndGF99" to 0.0,
        "MinNDL" to 0.0,
        "MaxDecoObligation" to 0.0,
        "AveloMaxPressure" to -1,
        "AveloMinBuoyancy" to Int.MAX_VALUE,
        "AveloMaxBuoyancy" to Int.MIN_VALUE,
        "AveloAverageBuoyancy" to -1,
        "AveloMinRMV" to -1.0,
        "AveloMaxRMV" to -1.0,
    )

    /** Depth and temperature over the submerged part of the dive's main profile. */
    private class Stats(val meanDepthM: Double?, val meanTempC: Double?, val minTempC: Double?, val maxTempC: Double?) {
        companion object {
            fun of(dive: DiveEntry): Stats {
                val samples = dive.computers.firstOrNull { it.samples.isNotEmpty() }?.samples.orEmpty()
                    .filter { (it.depthMm ?: 0) > 0 }
                val temps = samples.mapNotNull { it.temperatureMk }.map { (it - 273_150) / 1000.0 }
                val meanDepth = samples.mapNotNull { it.depthMm }.takeIf { it.isNotEmpty() }?.average()?.div(1000)
                    ?: dive.meanDepthMm?.div(1000.0)
                return Stats(
                    meanDepth,
                    temps.takeIf { it.isNotEmpty() }?.average(),
                    temps.minOrNull() ?: dive.waterTempMk?.let { (it - 273_150) / 1000.0 },
                    temps.maxOrNull(),
                )
            }
        }
    }

    /** A Shearwater computer's serial; another vendor's would be taken for a Shearwater unit. */
    private fun shearwaterSerial(dive: DiveEntry): String? = dive.computers
        .filter { ComputerNames.split(it.model).first == "Shearwater" }
        .firstNotNullOfOrNull { it.serial?.uppercase()?.takeIf { s -> SERIAL.matches(s) } }

    /** "<uuid>-<start>", the form Shearwater Cloud gives dives it imported. Stable per position and start. */
    private fun diveId(index: Int, dive: DiveEntry): String =
        "73796e74-6864-4000-8000-${(index + 1).toString(16).padStart(12, '0')}-${dive.startEpochSeconds}"

    /** "2024-06-30 14:05:00" for epoch seconds read as UTC. */
    private fun stamp(epochSeconds: Long): String {
        val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC)
        return "${t.year}-${p(t.month.ordinal + 1)}-${p(t.day)} ${p(t.hour)}:${p(t.minute)}:${p(t.second)}"
    }

    /** "06/30/2024 14:05:00", the form of the `DateAndTime` field. */
    private fun usStamp(epochSeconds: Long): String {
        val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC)
        return "${p(t.month.ordinal + 1)}/${p(t.day)}/${t.year} ${p(t.hour)}:${p(t.minute)}:${p(t.second)}"
    }

    private fun p(v: Int) = v.toString().padStart(2, '0')

    private fun psi(mbar: Int): Int = (mbar / MBAR_PER_PSI).roundToInt()

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to json(v) })

    private fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> error("Unsupported JSON value $v")
    }

    private const val TABLE_VERSION = 3L
    private const val TANK_SLOTS = 6
    private const val OPEN_CIRCUIT = 1
    private const val SURFACE_MBAR = 1013.0
    private const val SALINITY = 1020
    private const val MBAR_PER_PSI = 68.9476
    private val SERIAL = Regex("[0-9A-F]{8}")

    /** The device the records say they came from; any stable GUID. */
    private const val DEVICE_ID = "73796e74-6864-4976-656c-6f6764697665"

    /** .NET's DateTime.MinValue in Unix milliseconds: a field never set. */
    private const val NEVER_MS = -62_135_596_800_000L

    /** The logbook fields Shearwater Cloud keeps a timestamp for. */
    private val SYNCED_FIELDS = listOf(
        "GnssEntryLocation", "GnssExitLocation", "TankProfileData", "EnvironmentNotes", "Location", "Site", "Buddy",
        "DateAndTime", "DiveNumber", "Environment", "Visibility", "Weather", "Conditions", "Platform", "AirTemperature",
        "Tank1PressureStart", "Tank1PressureEnd", "Tank2PressureStart", "Tank2PressureEnd", "Tank3PressureStart",
        "Tank3PressureEnd", "Tank4PressureStart", "Tank4PressureEnd", "AverageSAC", "TankSize", "GasNotes", "Weight",
        "GearNotes", "Dress", "Apparatus", "BreathingGas", "Decompression", "ThermalComfort", "Workload", "Problems",
        "Malfunctions", "Symptoms", "ExposureToAltitude", "Notes", "Other1", "Other2", "Other3", "IssueNotes", "AveloNotes",
    )
}
