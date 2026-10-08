package no.synth.divelog.core.logbook.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.shearwater.PredatorDump
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.SiteRef
import no.synth.divelog.core.formats.TankEntry
import no.synth.divelog.core.formats.UddfFormat
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.BAR_PER_PSI
import no.synth.divelog.core.model.units.MM_PER_FOOT
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK
import kotlin.math.roundToInt

/**
 * A dive from a Shearwater Cloud export: its logbook [entry], and [parsed] when the export
 * keeps the computer's raw log, which is then stored and can be re-parsed like a download.
 */
internal class CloudDive(val entry: DiveEntry, val parsed: IncomingDive?)

/** A log that could not be read, and why. */
internal class CloudFailure(val logId: String, val message: String)

/** The export's dives, and the logs that could not be read. */
internal class CloudDives(val dives: List<CloudDive>, val failures: List<CloudFailure>) {
    val unreadable: Int get() = failures.size
}

/**
 * Turns a [ShearwaterCloudExport] into dives. Each log is one of four forms: the raw
 * computer log in record form or in the Predator block form (parsed by [PredatorParser]),
 * Shearwater's own decoded copy as JSON, or a UDDF document. The logbook fields (site,
 * buddy, notes, tanks) fill in what the log lacks. The computer is named only "Shearwater";
 * its serial ties it to a computer already downloaded or imported.
 */
internal object ShearwaterCloudDives {
    fun read(export: ShearwaterCloudExport): CloudDives {
        val failures = mutableListOf<CloudFailure>()
        val dives = export.logs.mapNotNull { log ->
            runCatching { dive(log, export.details[log.id].orEmpty()) }.fold(
                onSuccess = { it ?: run { failures += CloudFailure(log.id, "No readable log (format ${log.format})"); null } },
                onFailure = { failures += CloudFailure(log.id, it.message ?: it::class.simpleName ?: "error"); null },
            )
        }
        return CloudDives(dives, failures)
    }

    private fun dive(log: ShearwaterCloudLog, details: Map<String, String>): CloudDive? {
        val blob = log.blobs.firstOrNull() ?: return null
        return when (log.format) {
            "sw-pnf" -> raw(log, blob, PredatorParser.PNF_FORMAT_ID, details, recordSerial(log))
            "sw-predator" -> raw(log, blob, PredatorDump.FORMAT_ID, details, null)
            "sw-clouddb" -> decoded(log, blob, details)
            "uddf" -> uddf(blob, details)
            else -> null
        }
    }

    private fun raw(log: ShearwaterCloudLog, data: ByteArray, formatId: String, details: Map<String, String>, logSerial: String?): CloudDive {
        val parsed = PredatorParser().parse(RawDive("shearwater-cloud:${log.id}", data, formatId))
        val entry = DiveEntry(
            number = parsed.number,
            startEpochSeconds = parsed.startEpochSeconds,
            durationSeconds = parsed.durationSeconds,
            maxDepthMm = parsed.maxDepthMm,
            meanDepthMm = parsed.meanDepthMm,
            waterTempMk = parsed.waterTempMk,
            computers = listOf(computer(serial(details) ?: logSerial)),
        )
        return CloudDive(withDetails(entry, details), parsed)
    }

    /** Shearwater's decoded copy: a header object and an array of samples (time in ms). */
    private fun decoded(log: ShearwaterCloudLog, headerBlob: ByteArray, details: Map<String, String>): CloudDive {
        val header = json(headerBlob).jsonObject
        val imperial = header.long("imperialUnits") == 1L
        val rows = log.blobs.getOrNull(1)?.let { json(it).jsonArray }.orEmpty()
        val samples = ArrayList<Sample>()
        val events = ArrayList<Event>()
        var gas: Pair<Int, Int>? = null
        for (row in rows) {
            val r = row.jsonObject
            val time = ((r.double("currentTime") ?: continue) / 1000).roundToInt()
            val depth = r.double("currentDepth")
            samples += Sample(
                timeOffsetSeconds = time,
                depthMm = depth?.let { mm(it, imperial) },
                temperatureMk = r.double("waterTemp")?.let { mk(it, imperial) },
                ppO2Mbar = r.double("averagePPO2")?.takeIf { it > 0 }?.let { (it * 1000).roundToInt() },
                ndlSeconds = r.long("currentNdl")?.takeIf { it in 1..98 }?.let { (it * 60).toInt() },
                cnsPermille = r.long("CNSPercent")?.let { (it * 10).toInt() },
            )
            val o2 = r.double("fractionO2")?.roundToInt() ?: 0
            val he = r.double("fractionHe")?.roundToInt() ?: 0
            if (o2 > 0 && (o2 to he) != gas) {
                gas = o2 to he
                events += Event(timeOffsetSeconds = time, type = EventType.GAS_SWITCH, value = GasSwitch.value(o2, he))
            }
        }
        // Drop the surface tail the computer keeps logging after the dive.
        while (samples.size > 1 && (samples.last().depthMm ?: 0) == 0) samples.removeAt(samples.lastIndex)
        val lastTime = samples.lastOrNull()?.timeOffsetSeconds ?: 0
        events.retainAll { it.timeOffsetSeconds <= lastTime }

        val submerged = samples.mapNotNull { it.depthMm }.filter { it > 0 }
        val entry = DiveEntry(
            number = header.long("number")?.toInt(),
            startEpochSeconds = header.string("startDate")?.toLongOrNull() ?: 0,
            durationSeconds = header.long("FooterDiveTimeInSeconds")?.toInt() ?: lastTime,
            maxDepthMm = submerged.maxOrNull() ?: header.double("maxDepthFloat")?.let { mm(it, imperial) },
            meanDepthMm = if (submerged.isNotEmpty()) submerged.sum() / submerged.size else null,
            waterTempMk = samples.filter { (it.depthMm ?: 0) > 0 }.mapNotNull { it.temperatureMk }.minOrNull(),
            gasMixes = events.mapNotNull { it.value }.distinct()
                .map { GasMix(o2Permille = GasSwitch.o2Percent(it) * 10, hePermille = GasSwitch.hePercent(it) * 10) },
            computers = listOf(computer(serial(details) ?: decodedSerial(header), samples, events)),
        )
        return CloudDive(withDetails(entry, details), null)
    }

    private fun uddf(blob: ByteArray, details: Map<String, String>): CloudDive? {
        val base = UddfFormat().read(blob.decodeToString()).dives.firstOrNull() ?: return null
        // These came into Shearwater Cloud from other logbooks; the UDDF names the computer.
        val serial = serial(details)
        val computers = if (serial == null) base.computers else base.computers.map { it.copy(serial = it.serial ?: serial) }
        return CloudDive(withDetails(base.copy(computers = computers), details), null)
    }

    /** The logbook fields, filling only what [entry] does not already have. */
    private fun withDetails(entry: DiveEntry, d: Map<String, String>): DiveEntry {
        val tanks = tanks(d)
        return entry.copy(
            number = entry.number ?: d["DiveNumber"]?.trim()?.toIntOrNull(),
            notes = entry.notes ?: d["Notes"]?.trim()?.ifEmpty { null },
            buddies = entry.buddies.ifEmpty { d["Buddy"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() } },
            site = mergeSite(entry.site, site(d)),
            tanks = entry.tanks.ifEmpty { tanks },
            gasMixes = entry.gasMixes.ifEmpty { gasProfiles(d) },
        )
    }

    /** The log's own site, with what it lacks taken from the logbook fields. */
    private fun mergeSite(own: SiteRef?, fields: SiteRef?): SiteRef? = when {
        own == null || own.name.isBlank() -> fields ?: own
        fields == null -> own
        else -> own.copy(place = own.place ?: fields.place, country = own.country ?: fields.country)
    }

    /** "Site" is the site; "Location" the place around it, or the site when that is all there is. */
    private fun site(d: Map<String, String>): SiteRef? {
        val site = d["Site"]?.trim()?.ifEmpty { null }
        val location = d["Location"]?.trim()?.ifEmpty { null }
        return when {
            site != null -> SiteRef(name = site, place = location)
            location != null -> SiteRef(name = location)
            else -> null
        }
    }

    /** Tanks from the tank profile (gas, pressures in psi) and the sizes (m³, comma-separated). */
    private fun tanks(d: Map<String, String>): List<TankEntry> {
        val sizes = d["TankSize"].orEmpty().split(',').map { it.trim().toDoubleOrNull()?.takeIf { v -> v > 0 } }
        val tankData = d["TankProfileData"]?.let { runCatching { json(it.encodeToByteArray()).jsonObject }.getOrNull() }
            ?.get("TankData")?.let { it as? JsonArray }.orEmpty().map { it.jsonObject }
        val count = maxOf(tankData.size, sizes.count { it != null })
        return (0 until count).mapNotNull { i ->
            val t = tankData.getOrNull(i)
            val gas = t?.get("GasProfile")?.let { it as? JsonObject }
            val o2 = gas?.long("O2Percent")?.takeIf { it > 0 }
            val tank = TankEntry(
                index = i,
                volumeMl = sizes.getOrNull(i)?.let { (it * 1_000_000).roundToInt() },
                startPressureMbar = t?.string("StartPressurePSI")?.let(::psiToMbar),
                endPressureMbar = t?.string("EndPressurePSI")?.let(::psiToMbar),
                o2Permille = o2?.let { (it * 10).toInt() },
                hePermille = o2?.let { ((gas?.long("HePercent") ?: 0) * 10).toInt() },
            )
            tank.takeIf { it.volumeMl != null || it.startPressureMbar != null || it.endPressureMbar != null || it.o2Permille != null }
        }
    }

    private fun gasProfiles(d: Map<String, String>): List<GasMix> =
        d["TankProfileData"]?.let { runCatching { json(it.encodeToByteArray()).jsonObject }.getOrNull() }
            ?.get("GasProfiles")?.let { it as? JsonArray }.orEmpty()
            .mapNotNull { p ->
                val o = p.jsonObject
                val o2 = o.long("O2Percent")?.takeIf { it > 0 } ?: return@mapNotNull null
                GasMix(o2Permille = (o2 * 10).toInt(), hePermille = ((o.long("HePercent") ?: 0) * 10).toInt())
            }.distinct()

    private fun computer(serial: String?, samples: List<Sample> = emptyList(), events: List<Event> = emptyList()) =
        ComputerEntry(model = SHEARWATER, serial = serial, samples = samples, events = events)

    /** The logbook's serial: eight hex digits, as other logbooks write it. */
    private fun serial(d: Map<String, String>): String? = d["SerialNumber"]?.trim()?.uppercase()?.ifEmpty { null }

    /** The record log's own serial, a decimal number, written the same way. */
    private fun recordSerial(log: ShearwaterCloudLog): String? =
        log.blobs.getOrNull(1)?.let { runCatching { json(it).jsonObject.long("SERIAL_NUMBER_KEY") }.getOrNull() }?.let(::hexSerial)

    private fun decodedSerial(header: JsonObject): String? =
        (header.long("computerSerial") ?: header.long("startComputerSerialNumber"))?.takeIf { it > 0 }?.let(::hexSerial)

    private fun hexSerial(value: Long) = value.toString(16).uppercase().padStart(8, '0')

    private fun psiToMbar(text: String): Int? = text.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * (BAR_PER_PSI * 1000)).roundToInt() }

    private fun mm(depth: Double, imperial: Boolean) = (depth * if (imperial) MM_PER_FOOT else 1000.0).roundToInt()

    private fun mk(temp: Double, imperial: Boolean): Int {
        val celsius = if (imperial) (temp - 32) * 5 / 9 else temp
        return (celsius * 1000).roundToInt() + ZERO_CELSIUS_MK
    }

    private fun json(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString || it.longOrNull != null }?.content

    private const val SHEARWATER = "Shearwater"
}
