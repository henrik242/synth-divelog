package no.synth.divelog.core.formats

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.xmlStreaming
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType as DiveEventType
import no.synth.divelog.core.model.Sample

/**
 * Reads and writes the Subsurface XML dive-log format. The format name is shown
 * to the user so they recognise what they are importing or exporting.
 *
 * Multiple `divecomputer` elements per dive are supported; depths are metres,
 * temperatures Celsius, times "M:SS min".
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

        // Serials live in the settings, keyed by a device id each dive computer refers to.
        val serialComputers = log.dives.flatMap { it.computers }.filter { it.serial != null }
            .distinctBy { it.model to it.serial }
        if (serialComputers.isNotEmpty()) {
            w.startTag(NS, "settings", "")
            for (c in serialComputers) {
                w.startTag(NS, "divecomputerid", "")
                c.model?.let { w.attribute(NS, "model", "", it) }
                w.attribute(NS, "deviceid", "", deviceId(c))
                c.serial?.let { w.attribute(NS, "serial", "", it) }
                w.endTag(NS, "divecomputerid", "")
            }
            w.endTag(NS, "settings", "")
        }

        val siteIds = log.dives.mapNotNull { it.site }.map { it.name }.distinct()
            .associateWith { siteUuid(it) }
        if (siteIds.isNotEmpty()) {
            w.startTag(NS, "divesites", "")
            log.dives.mapNotNull { it.site }.distinctBy { it.name }.forEach { site ->
                w.startTag(NS, "site", "")
                w.attribute(NS, "uuid", "", siteIds.getValue(site.name))
                w.attribute(NS, "name", "", fullSiteName(site))
                if (site.latitude != null && site.longitude != null) {
                    w.attribute(NS, "gps", "", "${site.latitude} ${site.longitude}")
                }
                w.endTag(NS, "site", "")
            }
            w.endTag(NS, "divesites", "")
        }

        w.startTag(NS, "dives", "")
        for (dive in log.dives) writeDive(w, dive, dive.site?.let { siteIds[it.name] })
        w.endTag(NS, "dives", "")

        w.endTag(NS, "divelog", "")
        w.close()
        return out.toString()
    }

    private fun writeDive(w: XmlWriter, dive: DiveEntry, siteUuid: String?) {
        w.startTag(NS, "dive", "")
        dive.number?.let { w.attribute(NS, "number", "", it.toString()) }
        w.attribute(NS, "date", "", FormatDateTime.date(dive.startEpochSeconds, dive.utcOffsetSeconds))
        w.attribute(NS, "time", "", FormatDateTime.time(dive.startEpochSeconds, dive.utcOffsetSeconds))
        w.attribute(NS, "duration", "", FormatUnits.secondsToClock(dive.durationSeconds))
        dive.rating?.let { w.attribute(NS, "rating", "", it.toString()) }
        dive.visibility?.let { w.attribute(NS, "visibility", "", it.toString()) }
        if (dive.tags.isNotEmpty()) w.attribute(NS, "tags", "", dive.tags.joinToString(", "))
        siteUuid?.let { w.attribute(NS, "divesiteid", "", it) }

        for (buddy in dive.buddies) textElement(w, "buddy", buddy)
        dive.notes?.let { textElement(w, "notes", it) }

        for (tank in dive.tanks) {
            w.startTag(NS, "cylinder", "")
            w.attribute(NS, "index", "", tank.index.toString())
            tank.volumeMl?.let { w.attribute(NS, "size", "", FormatUnits.mlToLitres(it)) }
            tank.workingPressureMbar?.let { w.attribute(NS, "workpressure", "", FormatUnits.mbarToBar(it)) }
            tank.startPressureMbar?.let { w.attribute(NS, "start", "", FormatUnits.mbarToBar(it)) }
            tank.endPressureMbar?.let { w.attribute(NS, "end", "", FormatUnits.mbarToBar(it)) }
            tank.o2Permille?.let { w.attribute(NS, "o2", "", FormatUnits.permilleToPercent(it)) }
            tank.hePermille?.let { w.attribute(NS, "he", "", FormatUnits.permilleToPercent(it)) }
            w.endTag(NS, "cylinder", "")
        }

        for (computer in dive.computers) writeComputer(w, computer)
        w.endTag(NS, "dive", "")
    }

    private fun writeComputer(w: XmlWriter, computer: ComputerEntry) {
        w.startTag(NS, "divecomputer", "")
        computer.model?.let { w.attribute(NS, "model", "", it) }
        if (computer.serial != null) w.attribute(NS, "deviceid", "", deviceId(computer))

        if (computer.maxDepthMm != null || computer.meanDepthMm != null) {
            w.startTag(NS, "depth", "")
            computer.maxDepthMm?.let { w.attribute(NS, "max", "", FormatUnits.depthToMetres(it)) }
            computer.meanDepthMm?.let { w.attribute(NS, "mean", "", FormatUnits.depthToMetres(it)) }
            w.endTag(NS, "depth", "")
        }
        if (computer.waterTempMk != null || computer.airTempMk != null) {
            w.startTag(NS, "temperature", "")
            computer.waterTempMk?.let { w.attribute(NS, "water", "", FormatUnits.tempToCelsius(it)) }
            computer.airTempMk?.let { w.attribute(NS, "air", "", FormatUnits.tempToCelsius(it)) }
            w.endTag(NS, "temperature", "")
        }

        for (s in computer.samples) {
            w.startTag(NS, "sample", "")
            w.attribute(NS, "time", "", FormatUnits.secondsToClock(s.timeOffsetSeconds))
            s.depthMm?.let { w.attribute(NS, "depth", "", FormatUnits.depthToMetres(it)) }
            s.temperatureMk?.let { w.attribute(NS, "temp", "", FormatUnits.tempToCelsius(it)) }
            s.ndlSeconds?.let { w.attribute(NS, "ndl", "", FormatUnits.secondsToClock(it)) }
            s.stopDepthMm?.let { w.attribute(NS, "stopdepth", "", FormatUnits.depthToMetres(it)) }
            s.stopTimeSeconds?.let { w.attribute(NS, "stoptime", "", FormatUnits.secondsToClock(it)) }
            w.endTag(NS, "sample", "")
        }
        for (e in computer.events) {
            w.startTag(NS, "event", "")
            w.attribute(NS, "time", "", FormatUnits.secondsToClock(e.timeOffsetSeconds))
            w.attribute(NS, "name", "", eventName(e.type))
            val gasValue = e.value
            if (e.type == DiveEventType.GAS_SWITCH && gasValue != null) {
                val o2 = ((gasValue shr 8) and 0xFF).toInt()
                val he = (gasValue and 0xFF).toInt()
                w.attribute(NS, "o2", "", "$o2.0%")
                if (he > 0) w.attribute(NS, "he", "", "$he.0%")
            }
            w.endTag(NS, "event", "")
        }
        w.endTag(NS, "divecomputer", "")
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

        var dive: DiveBuilder? = null
        var computer: ComputerBuilder? = null
        var capture: StringBuilder? = null
        var captureName: String? = null

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> when (reader.localName) {
                        "site" -> {
                            val uuid = attr(reader, "uuid")
                            val name = attr(reader, "name")
                            if (uuid != null && name != null) sitesByUuid[uuid] = parseSite(name, attr(reader, "gps"))
                        }
                        "divecomputerid" -> {
                            val id = attr(reader, "deviceid")
                            val serial = attr(reader, "serial")?.trim()
                            if (id != null && !serial.isNullOrEmpty()) serialsByDeviceId[id] = serial
                        }
                        "dive" -> dive = startDive(reader)
                        "buddy", "notes" -> { capture = StringBuilder(); captureName = reader.localName }
                        "cylinder" -> dive?.tanks?.add(parseTank(reader))
                        "divecomputer" -> computer = ComputerBuilder(
                            model = attr(reader, "model"),
                            serial = attr(reader, "deviceid")?.let { serialsByDeviceId[it] },
                        )
                        "depth" -> computer?.let {
                            it.maxDepthMm = attr(reader, "max")?.let(FormatUnits::metresToMm)
                            it.meanDepthMm = attr(reader, "mean")?.let(FormatUnits::metresToMm)
                        }
                        "temperature" -> computer?.let {
                            it.waterTempMk = attr(reader, "water")?.let(FormatUnits::celsiusToMk)
                            it.airTempMk = attr(reader, "air")?.let(FormatUnits::celsiusToMk)
                        }
                        "sample" -> computer?.samples?.add(parseSample(reader))
                        "event" -> computer?.events?.add(parseEvent(reader))
                    }

                    EventType.TEXT, EventType.CDSECT -> capture?.append(reader.text)

                    EventType.END_ELEMENT -> when (reader.localName) {
                        "buddy" -> { dive?.buddies?.add(capture.toString().trim()); capture = null; captureName = null }
                        "notes" -> { dive?.notes = capture.toString().trim(); capture = null; captureName = null }
                        "divecomputer" -> { computer?.let { dive?.computers?.add(it) }; computer = null }
                        "dive" -> { dive?.let { dives.add(it.build(sitesByUuid)) }; dive = null }
                    }

                    else -> {}
                }
            }
        } catch (e: Exception) {
            throw FormatException("Malformed Subsurface XML", e)
        }
        return DiveLog(dives)
    }

    private fun startDive(reader: nl.adaptivity.xmlutil.XmlReader): DiveBuilder {
        val date = attr(reader, "date")
        val time = attr(reader, "time") ?: "00:00:00"
        return DiveBuilder(
            number = attr(reader, "number")?.toIntOrNull(),
            startEpochSeconds = if (date != null) FormatDateTime.epochFromDateTime(date, time) else 0L,
            durationSeconds = attr(reader, "duration")?.let(FormatUnits::clockToSeconds) ?: 0,
            rating = attr(reader, "rating")?.toIntOrNull(),
            visibility = attr(reader, "visibility")?.toIntOrNull(),
            siteUuid = attr(reader, "divesiteid"),
            tags = attr(reader, "tags")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList(),
        )
    }

    private fun parseTank(reader: nl.adaptivity.xmlutil.XmlReader): TankEntry = TankEntry(
        index = attr(reader, "index")?.toIntOrNull() ?: 0,
        volumeMl = attr(reader, "size")?.let(FormatUnits::litresToMl),
        workingPressureMbar = attr(reader, "workpressure")?.let(FormatUnits::barToMbar),
        startPressureMbar = attr(reader, "start")?.let(FormatUnits::barToMbar),
        endPressureMbar = attr(reader, "end")?.let(FormatUnits::barToMbar),
        o2Permille = attr(reader, "o2")?.let(FormatUnits::percentToPermille),
        hePermille = attr(reader, "he")?.let(FormatUnits::percentToPermille),
    )

    private fun parseSample(reader: nl.adaptivity.xmlutil.XmlReader): Sample = Sample(
        timeOffsetSeconds = attr(reader, "time")?.let(FormatUnits::clockToSeconds) ?: 0,
        depthMm = attr(reader, "depth")?.let(FormatUnits::metresToMm),
        temperatureMk = attr(reader, "temp")?.let(FormatUnits::celsiusToMk),
        ndlSeconds = attr(reader, "ndl")?.let(FormatUnits::clockToSeconds),
        stopDepthMm = attr(reader, "stopdepth")?.let(FormatUnits::metresToMm),
        stopTimeSeconds = attr(reader, "stoptime")?.let(FormatUnits::clockToSeconds),
    )

    private fun parseEvent(reader: nl.adaptivity.xmlutil.XmlReader): Event {
        val type = eventType(attr(reader, "name"))
        val time = attr(reader, "time")?.let(FormatUnits::clockToSeconds) ?: 0
        val value = if (type == DiveEventType.GAS_SWITCH) {
            val o2 = attr(reader, "o2")?.let(FormatUnits::leadingNumber)?.toInt() ?: 0
            val he = attr(reader, "he")?.let(FormatUnits::leadingNumber)?.toInt() ?: 0
            ((o2 shl 8) or he).toLong()
        } else {
            null
        }
        return Event(timeOffsetSeconds = time, type = type, value = value)
    }

    private fun parseSite(name: String, gps: String?): SiteRef {
        val coords = gps?.trim()?.split(Regex("\\s+"))
        val lat = coords?.getOrNull(0)?.toDoubleOrNull()
        val lon = coords?.getOrNull(1)?.toDoubleOrNull()
        // Split "Country / Place / Site" best-effort.
        val parts = name.split(" / ")
        return when (parts.size) {
            3 -> SiteRef(parts[2], parts[0], parts[1], lat, lon)
            2 -> SiteRef(parts[1], parts[0], null, lat, lon)
            else -> SiteRef(name, null, null, lat, lon)
        }
    }

    private fun attr(reader: nl.adaptivity.xmlutil.XmlReader, name: String): String? =
        reader.getAttributeValue(null, name)

    private fun fullSiteName(site: SiteRef): String =
        listOfNotNull(site.country, site.place, site.name).joinToString(" / ")

    private fun siteUuid(name: String): String {
        var h = 2166136261u
        for (c in name) { h = h xor c.code.toUInt(); h *= 16777619u }
        return h.toString(16).padStart(8, '0').take(8)
    }

    private fun eventName(type: DiveEventType): String = when (type) {
        DiveEventType.GAS_SWITCH -> "gaschange"
        DiveEventType.BOOKMARK -> "bookmark"
        DiveEventType.WARNING -> "warning"
        DiveEventType.DECO -> "deco"
        DiveEventType.SURFACE -> "surface"
        DiveEventType.ASCENT_RATE -> "ascent"
        DiveEventType.OTHER -> "event"
    }

    private fun eventType(name: String?): DiveEventType = when (name) {
        "gaschange" -> DiveEventType.GAS_SWITCH
        "bookmark" -> DiveEventType.BOOKMARK
        "warning" -> DiveEventType.WARNING
        "deco" -> DiveEventType.DECO
        "surface" -> DiveEventType.SURFACE
        "ascent" -> DiveEventType.ASCENT_RATE
        else -> DiveEventType.OTHER
    }

    private class DiveBuilder(
        val number: Int?,
        val startEpochSeconds: Long,
        val durationSeconds: Int,
        val rating: Int?,
        val visibility: Int?,
        val siteUuid: String?,
        val tags: List<String> = emptyList(),
        var notes: String? = null,
        val buddies: MutableList<String> = mutableListOf(),
        val tanks: MutableList<TankEntry> = mutableListOf(),
        val computers: MutableList<ComputerBuilder> = mutableListOf(),
    ) {
        fun build(sites: Map<String, SiteRef>): DiveEntry {
            val primary = computers.firstOrNull()
            return DiveEntry(
                number = number,
                startEpochSeconds = startEpochSeconds,
                utcOffsetSeconds = 0,
                durationSeconds = durationSeconds,
                maxDepthMm = primary?.maxDepthMm,
                meanDepthMm = primary?.meanDepthMm,
                waterTempMk = primary?.waterTempMk,
                airTempMk = primary?.airTempMk,
                notes = notes,
                rating = rating,
                visibility = visibility,
                site = siteUuid?.let { sites[it] },
                buddies = buddies,
                tags = tags,
                tanks = tanks,
                computers = computers.map { it.build() },
            )
        }
    }

    private class ComputerBuilder(
        val model: String?,
        val serial: String? = null,
        var maxDepthMm: Int? = null,
        var meanDepthMm: Int? = null,
        var waterTempMk: Int? = null,
        var airTempMk: Int? = null,
        val samples: MutableList<Sample> = mutableListOf(),
        val events: MutableList<Event> = mutableListOf(),
    ) {
        fun build() = ComputerEntry(model, maxDepthMm, meanDepthMm, waterTempMk, airTempMk, samples, events, serial)
    }

    /** A stable 8-hex-digit id tying a dive computer to its settings entry. */
    private fun deviceId(c: ComputerEntry): String =
        "${c.model}:${c.serial}".hashCode().toUInt().toString(16).padStart(8, '0')

    private companion object {
        const val NS = ""
    }
}
