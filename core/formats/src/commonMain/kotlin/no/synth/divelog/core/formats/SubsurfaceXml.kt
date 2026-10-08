package no.synth.divelog.core.formats

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.xmlStreaming
import no.synth.divelog.core.formats.SubsurfaceShared.milli
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType as DiveEventType
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK

/**
 * Reads and writes the Subsurface XML dive-log format. The format name is shown
 * to the user so they recognise what they are importing or exporting.
 *
 * Multiple `divecomputer` elements per dive are supported; depths are metres,
 * temperatures Celsius, times "M:SS min". Dive times are the local wall clock; the
 * UTC offset, when known, rides on the first dive computer as extra data. Sample
 * values other than tank pressures are written when they change and carry forward
 * on read.
 */
class SubsurfaceXml : DiveFormat {
    override val id: String = "subsurface-xml"
    override val displayName: String = "Subsurface XML"

    // --- Writing ---

    override fun write(log: DiveLog): String {
        val out = StringBuilder()
        val w: XmlWriter = KtXmlWriter(out, isRepairNamespaces = false)
        w.startTag(NS, "divelog", "")
        w.attribute(NS, "program", "", "synth-divelog")
        w.attribute(NS, "version", "", "3")

        // Serials live in the settings, keyed by the device id each dive computer refers to.
        val serialComputers = log.dives.flatMap { it.computers }.filter { it.serial != null }
            .distinctBy { it.model to it.serial }
        if (serialComputers.isNotEmpty()) {
            w.startTag(NS, "settings", "")
            for (c in serialComputers) {
                val serial = c.serial ?: continue
                w.startTag(NS, "divecomputerid", "")
                c.model?.let { w.attribute(NS, "model", "", it) }
                w.attribute(NS, "deviceid", "", SubsurfaceShared.deviceId(serial))
                w.attribute(NS, "serial", "", serial)
                w.endTag(NS, "divecomputerid", "")
            }
            w.endTag(NS, "settings", "")
        }

        val sites = log.dives.mapNotNull { it.site }.distinct()
        if (sites.isNotEmpty()) {
            w.startTag(NS, "divesites", "")
            for (site in sites) {
                w.startTag(NS, "site", "")
                w.attribute(NS, "uuid", "", SubsurfaceShared.siteUuid(site))
                w.attribute(NS, "name", "", site.name)
                if (site.latitude != null && site.longitude != null) {
                    w.attribute(NS, "gps", "", "${SubsurfaceShared.deg6(site.latitude)} ${SubsurfaceShared.deg6(site.longitude)}")
                }
                for ((cat, value) in SubsurfaceShared.geoOf(site)) {
                    w.startTag(NS, "geo", "")
                    w.attribute(NS, "cat", "", cat.toString())
                    w.attribute(NS, "origin", "", SubsurfaceShared.GEO_MANUAL.toString())
                    w.attribute(NS, "value", "", value)
                    w.endTag(NS, "geo", "")
                }
                w.endTag(NS, "site", "")
            }
            w.endTag(NS, "divesites", "")
        }

        w.startTag(NS, "dives", "")
        for (dive in log.dives) writeDive(w, dive)
        w.endTag(NS, "dives", "")

        w.endTag(NS, "divelog", "")
        w.close()
        return out.toString()
    }

    private fun writeDive(w: XmlWriter, dive: DiveEntry) {
        w.startTag(NS, "dive", "")
        dive.number?.let { w.attribute(NS, "number", "", it.toString()) }
        dive.rating?.let { w.attribute(NS, "rating", "", it.toString()) }
        // Subsurface's visibility is 0..5 stars, not a distance.
        dive.visibilityRating?.let { w.attribute(NS, "visibility", "", it.toString()) }
        if (dive.tags.isNotEmpty()) w.attribute(NS, "tags", "", dive.tags.joinToString(", "))
        dive.site?.let { w.attribute(NS, "divesiteid", "", SubsurfaceShared.siteUuid(it)) }
        w.attribute(NS, "date", "", FormatDateTime.date(dive.startEpochSeconds, dive.utcOffsetSeconds))
        w.attribute(NS, "time", "", FormatDateTime.time(dive.startEpochSeconds, dive.utcOffsetSeconds))
        if (dive.durationSeconds > 0) w.attribute(NS, "duration", "", "${SubsurfaceShared.clock(dive.durationSeconds)} min")

        // One buddy element; several buddies are comma-separated.
        if (dive.buddies.isNotEmpty()) textElement(w, "buddy", dive.buddies.joinToString(", "))
        dive.notes?.takeIf { it.isNotBlank() }?.let { textElement(w, "notes", it) }

        val tanks = SubsurfaceShared.tanksToWrite(dive)
        for (tank in tanks) {
            w.startTag(NS, "cylinder", "")
            tank.volumeMl?.let { w.attribute(NS, "size", "", "${milli(it)} l") }
            tank.workingPressureMbar?.let { w.attribute(NS, "workpressure", "", "${milli(it)} bar") }
            tank.o2Permille?.let { o2 ->
                w.attribute(NS, "o2", "", SubsurfaceShared.percent(o2))
                tank.hePermille?.takeIf { it > 0 }?.let { w.attribute(NS, "he", "", SubsurfaceShared.percent(it)) }
            }
            tank.startPressureMbar?.let { w.attribute(NS, "start", "", "${milli(it)} bar") }
            tank.endPressureMbar?.let { w.attribute(NS, "end", "", "${milli(it)} bar") }
            w.endTag(NS, "cylinder", "")
        }

        val computers = SubsurfaceShared.computersToWrite(dive)
        // Dive temperatures are written only where they differ from what the computers give.
        val air = dive.airTempMk?.takeIf { it != SubsurfaceShared.meanTemp(computers.map { c -> c.airTempMk }) }
        val water = dive.waterTempMk?.takeIf { it != SubsurfaceShared.meanTemp(computers.map { c -> c.waterTempMk }) }
        if (air != null || water != null) {
            w.startTag(NS, "divetemperature", "")
            air?.let { w.attribute(NS, "air", "", "${milli(it - ZERO_CELSIUS_MK)} C") }
            water?.let { w.attribute(NS, "water", "", "${milli(it - ZERO_CELSIUS_MK)} C") }
            w.endTag(NS, "divetemperature", "")
        }

        computers.forEachIndexed { i, c -> writeComputer(w, dive, c, tanks, if (i == 0) dive.utcOffsetSeconds else 0) }
        w.endTag(NS, "dive", "")
    }

    private fun writeComputer(w: XmlWriter, dive: DiveEntry, computer: ComputerEntry, tanks: List<TankEntry>, utcOffset: Int) {
        w.startTag(NS, "divecomputer", "")
        computer.model?.let { w.attribute(NS, "model", "", it) }
        computer.serial?.let { w.attribute(NS, "deviceid", "", SubsurfaceShared.deviceId(it)) }
        computer.startEpochSeconds?.takeIf { it != dive.startEpochSeconds }?.let { start ->
            w.attribute(NS, "date", "", FormatDateTime.date(start, dive.utcOffsetSeconds))
            w.attribute(NS, "time", "", FormatDateTime.time(start, dive.utcOffsetSeconds))
        }
        computer.durationSeconds?.takeIf { it != dive.durationSeconds && it > 0 }?.let {
            w.attribute(NS, "duration", "", "${SubsurfaceShared.clock(it)} min")
        }

        if (computer.maxDepthMm != null || computer.meanDepthMm != null) {
            w.startTag(NS, "depth", "")
            computer.maxDepthMm?.let { w.attribute(NS, "max", "", "${milli(it)} m") }
            computer.meanDepthMm?.let { w.attribute(NS, "mean", "", "${milli(it)} m") }
            w.endTag(NS, "depth", "")
        }
        if (computer.waterTempMk != null || computer.airTempMk != null) {
            w.startTag(NS, "temperature", "")
            computer.airTempMk?.let { w.attribute(NS, "air", "", "${milli(it - ZERO_CELSIUS_MK)} C") }
            computer.waterTempMk?.let { w.attribute(NS, "water", "", "${milli(it - ZERO_CELSIUS_MK)} C") }
            w.endTag(NS, "temperature", "")
        }
        computer.serial?.let { extraData(w, SubsurfaceShared.KEY_SERIAL, it) }
        if (utcOffset != 0) extraData(w, SubsurfaceShared.KEY_UTC_OFFSET, SubsurfaceShared.offsetText(utcOffset))

        for (e in computer.events) {
            w.startTag(NS, "event", "")
            w.attribute(NS, "time", "", "${SubsurfaceShared.clock(e.timeOffsetSeconds)} min")
            SubsurfaceShared.eventTypeNumber(e)?.let { w.attribute(NS, "type", "", it.toString()) }
            SubsurfaceShared.eventValue(e)?.let { w.attribute(NS, "value", "", it.toString()) }
            w.attribute(NS, "name", "", SubsurfaceShared.eventName(e.type))
            val value = e.value
            if (e.type == DiveEventType.GAS_SWITCH && value != null) {
                val o2 = GasSwitch.o2Percent(value)
                val he = GasSwitch.hePercent(value)
                SubsurfaceShared.cylinderOf(tanks, o2, he)?.let { w.attribute(NS, "cylinder", "", it.toString()) }
                w.attribute(NS, "o2", "", "$o2.0%")
                if (he > 0) w.attribute(NS, "he", "", "$he.0%")
            }
            w.endTag(NS, "event", "")
        }

        var last = Sample(timeOffsetSeconds = 0)
        for (s in computer.samples) {
            w.startTag(NS, "sample", "")
            w.attribute(NS, "time", "", "${SubsurfaceShared.clock(s.timeOffsetSeconds)} min")
            s.depthMm?.let { w.attribute(NS, "depth", "", "${milli(it)} m") }
            s.temperatureMk?.takeIf { it != last.temperatureMk }?.let { w.attribute(NS, "temp", "", "${milli(it - ZERO_CELSIUS_MK)} C") }
            for ((tank, mbar) in s.tankPressuresMbar.entries.sortedBy { it.key }) {
                w.attribute(NS, "pressure$tank", "", "${milli(mbar)} bar")
            }
            s.ndlSeconds?.takeIf { it != last.ndlSeconds }?.let { w.attribute(NS, "ndl", "", "${SubsurfaceShared.clock(it)} min") }
            s.stopTimeSeconds?.takeIf { it != last.stopTimeSeconds }?.let { w.attribute(NS, "stoptime", "", "${SubsurfaceShared.clock(it)} min") }
            s.stopDepthMm?.takeIf { it != last.stopDepthMm }?.let { w.attribute(NS, "stopdepth", "", "${milli(it)} m") }
            s.cnsPermille?.takeIf { it != last.cnsPermille }?.let { w.attribute(NS, "cns", "", "${it / 10}%") }
            s.ppO2Mbar?.takeIf { it != last.ppO2Mbar }?.let { w.attribute(NS, "dc_supplied_ppo2", "", "${milli(it)} bar") }
            w.endTag(NS, "sample", "")
            last = SubsurfaceShared.carried(last, s)
        }
        w.endTag(NS, "divecomputer", "")
    }

    private fun extraData(w: XmlWriter, key: String, value: String) {
        w.startTag(NS, "extradata", "")
        w.attribute(NS, "key", "", key)
        w.attribute(NS, "value", "", value)
        w.endTag(NS, "extradata", "")
    }

    private fun textElement(w: XmlWriter, name: String, value: String) {
        w.startTag(NS, name, "")
        w.text(value)
        w.endTag(NS, name, "")
    }

    // --- Reading ---

    override fun read(text: String): DiveLog {
        val reader = try {
            xmlStreaming.newReader(text)
        } catch (e: Exception) {
            throw FormatException("Could not open XML", e)
        }
        val sitesByUuid = mutableMapOf<String, SiteRef>()
        val serialsByDeviceId = mutableMapOf<String, String>()
        val dives = mutableListOf<DiveEntry>()

        var site: SiteBuilder? = null
        var dive: DiveBuilder? = null
        var computer: ComputerBuilder? = null
        var capture: StringBuilder? = null

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> when (reader.localName) {
                        "site" -> site = SiteBuilder(
                            uuid = attr(reader, "uuid")?.trim()?.lowercase(),
                            name = attr(reader, "name").orEmpty(),
                            gps = attr(reader, "gps"),
                        )
                        "geo" -> site?.let { s ->
                            val cat = attr(reader, "cat")?.toIntOrNull()
                            val value = attr(reader, "value")?.trim()
                            if (cat != null && !value.isNullOrEmpty()) s.geo.getOrPut(cat) { value }
                        }
                        "divecomputerid" -> {
                            val id = attr(reader, "deviceid")?.trim()?.lowercase()
                            val serial = attr(reader, "serial")?.trim()
                            if (id != null && !serial.isNullOrEmpty()) serialsByDeviceId[id] = serial
                        }
                        "dive" -> dive = startDive(reader)
                        "buddy", "notes" -> if (dive != null && site == null) capture = StringBuilder()
                        "cylinder" -> dive?.let { it.tanks.add(parseTank(reader, it.tanks.size)) }
                        "divetemperature" -> dive?.let {
                            it.airTempMk = attr(reader, "air")?.let(FormatUnits::celsiusToMk)
                            it.waterTempMk = attr(reader, "water")?.let(FormatUnits::celsiusToMk)
                        }
                        "divecomputer" -> computer = ComputerBuilder(
                            model = attr(reader, "model"),
                            deviceId = attr(reader, "deviceid")?.trim()?.lowercase(),
                            date = attr(reader, "date"),
                            time = attr(reader, "time"),
                            durationSeconds = attr(reader, "duration")?.let(FormatUnits::clockToSeconds),
                        )
                        "depth" -> computer?.let {
                            it.maxDepthMm = attr(reader, "max")?.let(FormatUnits::thousandths)
                            it.meanDepthMm = attr(reader, "mean")?.let(FormatUnits::thousandths)
                        }
                        "temperature" -> computer?.let {
                            it.waterTempMk = attr(reader, "water")?.let(FormatUnits::celsiusToMk)
                            it.airTempMk = attr(reader, "air")?.let(FormatUnits::celsiusToMk)
                        }
                        "extradata" -> computer?.let { c ->
                            val key = attr(reader, "key")
                            val value = attr(reader, "value")?.trim()
                            if (key != null && !value.isNullOrEmpty()) c.extra[key] = value
                        }
                        "tanksensormapping" -> computer?.let { c ->
                            val sensor = attr(reader, "sensorid")?.toIntOrNull()
                            val tank = attr(reader, "cylinderindex")?.toIntOrNull()
                            if (sensor != null && tank != null) c.sensorTanks[sensor] = tank
                        }
                        "sample" -> computer?.let { it.samples.add(parseSample(reader, it.samples.lastOrNull())) }
                        "event" -> computer?.let { c -> dive?.let { d -> c.events.add(parseEvent(reader, d.tanks)) } }
                    }

                    EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF -> capture?.append(reader.text)

                    EventType.END_ELEMENT -> when (reader.localName) {
                        "site" -> {
                            site?.let { s -> s.uuid?.let { sitesByUuid[it] = s.build() } }
                            site = null
                        }
                        "buddy" -> capture?.let { c ->
                            dive?.buddies?.addAll(SubsurfaceShared.splitList(c.toString()))
                            capture = null
                        }
                        "notes" -> capture?.let { c ->
                            dive?.notes = c.toString().trim().ifEmpty { null }
                            capture = null
                        }
                        "divecomputer" -> { computer?.let { dive?.computers?.add(it) }; computer = null }
                        "dive" -> { dive?.let { dives.add(it.build(sitesByUuid, serialsByDeviceId)) }; dive = null }
                    }

                    else -> {}
                }
            }
        } catch (e: Exception) {
            throw FormatException("Malformed Subsurface XML", e)
        }
        return DiveLog(dives)
    }

    private fun startDive(reader: XmlReader): DiveBuilder {
        val date = attr(reader, "date")
        val time = attr(reader, "time") ?: "00:00:00"
        return DiveBuilder(
            number = attr(reader, "number")?.toIntOrNull(),
            wallEpochSeconds = if (date != null) FormatDateTime.epochFromDateTime(date, time) else 0L,
            durationSeconds = attr(reader, "duration")?.let(FormatUnits::clockToSeconds) ?: 0,
            rating = attr(reader, "rating")?.toIntOrNull(),
            visibilityRating = attr(reader, "visibility")?.toIntOrNull(),
            siteUuid = attr(reader, "divesiteid")?.trim()?.lowercase(),
            tags = attr(reader, "tags")?.let(SubsurfaceShared::splitList) ?: emptyList(),
        )
    }

    /** A cylinder. One written without a mix is left without a gas rather than assumed to be air. */
    private fun parseTank(reader: XmlReader, index: Int): TankEntry {
        val o2 = attr(reader, "o2")?.let(FormatUnits::percentToPermille)
        return TankEntry(
            index = index,
            volumeMl = attr(reader, "size")?.let(FormatUnits::thousandths),
            workingPressureMbar = attr(reader, "workpressure")?.let(FormatUnits::thousandths),
            startPressureMbar = attr(reader, "start")?.let(FormatUnits::thousandths),
            endPressureMbar = attr(reader, "end")?.let(FormatUnits::thousandths),
            o2Permille = o2,
            hePermille = attr(reader, "he")?.let(FormatUnits::percentToPermille) ?: o2?.let { 0 },
        )
    }

    /** A sample; values it leaves out carry over from [previous], except tank pressures. */
    private fun parseSample(reader: XmlReader, previous: Sample?): Sample {
        val pressures = mutableMapOf<Int, Int>()
        for (i in 0 until reader.attributeCount) {
            val name = reader.getAttributeLocalName(i)
            val tank = when {
                name == "pressure" -> 0
                name.startsWith("pressure") -> name.removePrefix("pressure").toIntOrNull()
                else -> null
            } ?: continue
            FormatUnits.thousandths(reader.getAttributeValue(i))?.takeIf { it > 0 }?.let { pressures[tank] = it }
        }
        return Sample(
            timeOffsetSeconds = attr(reader, "time")?.let(FormatUnits::clockToSeconds) ?: 0,
            depthMm = attr(reader, "depth")?.let(FormatUnits::thousandths) ?: previous?.depthMm,
            temperatureMk = attr(reader, "temp")?.let(FormatUnits::celsiusToMk) ?: previous?.temperatureMk,
            // Earlier exports of this app wrote the computer's ppO2 as po2.
            ppO2Mbar = (attr(reader, "dc_supplied_ppo2") ?: attr(reader, "po2"))?.let(FormatUnits::thousandths) ?: previous?.ppO2Mbar,
            ndlSeconds = attr(reader, "ndl")?.let(FormatUnits::clockToSeconds) ?: previous?.ndlSeconds,
            stopDepthMm = attr(reader, "stopdepth")?.let(FormatUnits::thousandths) ?: previous?.stopDepthMm,
            stopTimeSeconds = attr(reader, "stoptime")?.let(FormatUnits::clockToSeconds) ?: previous?.stopTimeSeconds,
            cnsPermille = attr(reader, "cns")?.let(FormatUnits::percentToPermille) ?: previous?.cnsPermille,
            tankPressuresMbar = pressures,
        )
    }

    private fun parseEvent(reader: XmlReader, tanks: List<TankEntry>): Event {
        val type = SubsurfaceShared.eventType(attr(reader, "name"))
        val time = attr(reader, "time")?.let(FormatUnits::clockToSeconds) ?: 0
        val value = if (type == DiveEventType.GAS_SWITCH) {
            SubsurfaceShared.gasSwitchValue(attr(reader, "o2"), attr(reader, "he"), attr(reader, "cylinder"), attr(reader, "value"), tanks)
        } else {
            attr(reader, "value")?.trim()?.toLongOrNull()
        }
        return Event(timeOffsetSeconds = time, type = type, value = value)
    }

    private fun attr(reader: XmlReader, name: String): String? =
        reader.getAttributeValue(null, name)

    private class SiteBuilder(val uuid: String?, val name: String, val gps: String?, val geo: MutableMap<Int, String> = mutableMapOf()) {
        fun build(): SiteRef {
            val coords = gps?.trim()?.split(Regex("\\s+"))
            return SubsurfaceShared.site(name, coords?.getOrNull(0)?.toDoubleOrNull(), coords?.getOrNull(1)?.toDoubleOrNull(), geo)
        }
    }

    private class DiveBuilder(
        val number: Int?,
        val wallEpochSeconds: Long,
        val durationSeconds: Int,
        val rating: Int?,
        val visibilityRating: Int?,
        val siteUuid: String?,
        val tags: List<String> = emptyList(),
        var notes: String? = null,
        var airTempMk: Int? = null,
        var waterTempMk: Int? = null,
        val buddies: MutableList<String> = mutableListOf(),
        val tanks: MutableList<TankEntry> = mutableListOf(),
        val computers: MutableList<ComputerBuilder> = mutableListOf(),
    ) {
        fun build(sites: Map<String, SiteRef>, serials: Map<String, String>): DiveEntry {
            val offset = SubsurfaceShared.utcOffset(computers.firstOrNull()?.extra)
            return SubsurfaceShared.dive(
                number = number,
                wallEpochSeconds = wallEpochSeconds,
                utcOffset = offset,
                durationSeconds = durationSeconds,
                rating = rating,
                visibilityRating = visibilityRating,
                site = siteUuid?.let { sites[it] },
                notes = notes,
                buddies = buddies,
                tags = tags,
                tanks = tanks,
                airTempMk = airTempMk,
                waterTempMk = waterTempMk,
                computers = computers.map { it.build(serials, offset) },
            )
        }
    }

    private class ComputerBuilder(
        val model: String?,
        val deviceId: String?,
        val date: String?,
        val time: String?,
        val durationSeconds: Int?,
        var maxDepthMm: Int? = null,
        var meanDepthMm: Int? = null,
        var waterTempMk: Int? = null,
        var airTempMk: Int? = null,
        val extra: MutableMap<String, String> = mutableMapOf(),
        val sensorTanks: MutableMap<Int, Int> = mutableMapOf(),
        val samples: MutableList<Sample> = mutableListOf(),
        val events: MutableList<Event> = mutableListOf(),
    ) {
        fun build(serials: Map<String, String>, utcOffset: Int): ComputerEntry {
            val serial = extra[SubsurfaceShared.KEY_SERIAL] ?: deviceId?.let { serials[it] }
            val start = SubsurfaceShared.computerStart(date, time, utcOffset)
            return ComputerEntry(
                model = model?.takeIf { it.isNotBlank() },
                maxDepthMm = maxDepthMm,
                meanDepthMm = meanDepthMm,
                waterTempMk = waterTempMk,
                airTempMk = airTempMk,
                samples = SubsurfaceShared.mapSensors(samples, sensorTanks),
                events = events,
                serial = serial,
                startEpochSeconds = start,
                durationSeconds = durationSeconds,
            )
        }
    }

    private companion object {
        const val NS = ""
    }
}
