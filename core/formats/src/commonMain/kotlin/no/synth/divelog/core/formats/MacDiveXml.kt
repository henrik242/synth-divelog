package no.synth.divelog.core.formats

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.xmlStreaming
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType as DiveEventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Sample

/**
 * Reads and writes the MacDive XML dive-log format. MacDive stores one computer
 * per dive, metric units (depth metres, temperature Celsius, pressure and ppO2
 * bar), sample times in seconds and no-deco times in minutes. Dive dates are
 * local wall clock with no zone, so they are kept with a zero UTC offset.
 */
class MacDiveXml : DiveFormat {
    override val id: String = "macdive-xml"
    override val displayName: String = "MacDive XML"

    /** The computer whose profile is exported: primary, else the first with samples. */
    private fun exportComputer(dive: DiveEntry): ComputerEntry? =
        dive.computers.firstOrNull { it.samples.isNotEmpty() } ?: dive.computers.firstOrNull()

    // --- Writing ---

    override fun write(log: DiveLog): String {
        val out = StringBuilder()
        val w: XmlWriter = KtXmlWriter(out, isRepairNamespaces = false)
        w.startDocument("1.0", "UTF-8", null)
        w.docdecl("dives SYSTEM \"$DOCTYPE_URL\"")
        w.startTag(NS, "dives", "")
        text(w, "units", "Metric")
        text(w, "schema", SCHEMA)
        for (dive in log.dives) writeDive(w, dive)
        w.endTag(NS, "dives", "")
        w.close()
        return out.toString()
    }

    private fun writeDive(w: XmlWriter, dive: DiveEntry) {
        val computer = exportComputer(dive)
        w.startTag(NS, "dive", "")
        text(w, "date", FormatDateTime.spaceDateTime(dive.startEpochSeconds, dive.utcOffsetSeconds))
        dive.number?.let { text(w, "diveNumber", it.toString()) }
        dive.rating?.let { text(w, "rating", it.toString()) }
        computer?.model?.let { text(w, "computer", it) }
        (computer?.maxDepthMm ?: dive.maxDepthMm)?.let { text(w, "maxDepth", FormatUnits.macDepth(it)) }
        (computer?.meanDepthMm ?: dive.meanDepthMm)?.let { text(w, "averageDepth", FormatUnits.macDepth(it)) }
        text(w, "duration", dive.durationSeconds.toString())
        dive.airTempMk?.let { text(w, "tempAir", FormatUnits.macCelsius(it)) }
        (computer?.waterTempMk ?: dive.waterTempMk)?.let { text(w, "tempLow", FormatUnits.macCelsius(it)) }
        dive.visibility?.let { text(w, "visibility", it.toString()) }
        dive.notes?.let { cdata(w, "notes", it) }

        dive.site?.let { writeSite(w, it) }

        if (dive.buddies.isNotEmpty()) {
            w.startTag(NS, "buddies", "")
            for (buddy in dive.buddies) text(w, "buddy", buddy)
            w.endTag(NS, "buddies", "")
        }

        if (dive.tags.isNotEmpty()) {
            w.startTag(NS, "tags", "")
            for (tag in dive.tags) text(w, "tag", tag)
            w.endTag(NS, "tags", "")
        }

        writeGases(w, dive)

        if (computer != null && computer.samples.isNotEmpty()) {
            w.startTag(NS, "samples", "")
            for (s in computer.samples) writeSample(w, s)
            w.endTag(NS, "samples", "")
        }
        if (computer != null && computer.events.isNotEmpty()) {
            w.startTag(NS, "events", "")
            for (e in computer.events) writeEvent(w, e)
            w.endTag(NS, "events", "")
        }
        w.endTag(NS, "dive", "")
    }

    private fun writeSite(w: XmlWriter, site: SiteRef) {
        w.startTag(NS, "site", "")
        site.country?.let { text(w, "country", it) }
        site.place?.let { text(w, "location", it) }
        text(w, "name", site.name)
        site.latitude?.let { text(w, "lat", it.toString()) }
        site.longitude?.let { text(w, "lon", it.toString()) }
        w.endTag(NS, "site", "")
    }

    private fun writeGases(w: XmlWriter, dive: DiveEntry) {
        val gases: List<Gas> = when {
            dive.tanks.isNotEmpty() -> dive.tanks.map {
                Gas(it.o2Permille, it.hePermille, it.volumeMl, it.workingPressureMbar, it.startPressureMbar, it.endPressureMbar)
            }
            else -> dive.gasMixes.map { Gas(it.o2Permille, it.hePermille, null, null, null, null) }
        }
        if (gases.isEmpty()) return
        w.startTag(NS, "gases", "")
        for (g in gases) {
            w.startTag(NS, "gas", "")
            g.startPressureMbar?.let { text(w, "pressureStart", FormatUnits.macBar(it)) }
            g.endPressureMbar?.let { text(w, "pressureEnd", FormatUnits.macBar(it)) }
            g.o2Permille?.let { text(w, "oxygen", FormatUnits.macPercent(it)) }
            g.hePermille?.let { text(w, "helium", FormatUnits.macPercent(it)) }
            g.volumeMl?.let { text(w, "tankSize", FormatUnits.macLitres(it)) }
            g.workingPressureMbar?.let { text(w, "workingPressure", FormatUnits.macBar(it)) }
            w.endTag(NS, "gas", "")
        }
        w.endTag(NS, "gases", "")
    }

    private fun writeSample(w: XmlWriter, s: Sample) {
        w.startTag(NS, "sample", "")
        text(w, "time", FormatUnits.twoDecimals(s.timeOffsetSeconds.toDouble()))
        s.depthMm?.let { text(w, "depth", FormatUnits.macDepth(it)) }
        s.tankPressuresMbar.values.firstOrNull()?.let { text(w, "pressure", FormatUnits.macBar(it)) }
        s.temperatureMk?.let { text(w, "temperature", FormatUnits.macCelsius(it)) }
        s.ppO2Mbar?.let { text(w, "ppo2", FormatUnits.macBar(it)) }
        s.ndlSeconds?.let { text(w, "ndt", FormatUnits.macMinutes(it)) }
        w.endTag(NS, "sample", "")
    }

    private fun writeEvent(w: XmlWriter, e: Event) {
        w.startTag(NS, "event", "")
        text(w, "type", eventTypeCode(e.type).toString())
        text(w, "time", FormatUnits.twoDecimals(e.timeOffsetSeconds.toDouble()))
        text(w, "name", eventName(e))
        w.endTag(NS, "event", "")
    }

    private fun text(w: XmlWriter, name: String, value: String) {
        w.startTag(NS, name, "")
        w.text(value)
        w.endTag(NS, name, "")
    }

    private fun cdata(w: XmlWriter, name: String, value: String) {
        w.startTag(NS, name, "")
        w.cdsect(value)
        w.endTag(NS, name, "")
    }

    // --- Reading ---

    override fun read(text: String): DiveLog {
        val reader = try {
            xmlStreaming.newReader(text)
        } catch (e: Exception) {
            throw FormatException("Could not open XML", e)
        }
        val dives = mutableListOf<DiveEntry>()

        var dive: DiveBuilder? = null
        var site: SiteBuilder? = null
        var gas: GasBuilder? = null
        var sample: SampleBuilder? = null
        var event: EventBuilder? = null
        // Scope flags: several child names (name, type, time, duration, depth) are
        // reused across site, gas, sample, event and gear, so route by what is open.
        var inGear = false
        var buf = StringBuilder()

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> {
                        buf = StringBuilder()
                        when (reader.localName) {
                            "dive" -> {
                                dive = DiveBuilder()
                                site = null; gas = null; sample = null; event = null; inGear = false
                            }
                            "site" -> site = SiteBuilder()
                            "gear" -> inGear = true
                            "gas" -> gas = GasBuilder()
                            "sample" -> sample = SampleBuilder()
                            "event" -> event = EventBuilder()
                        }
                    }

                    EventType.TEXT, EventType.CDSECT -> buf.append(reader.text)

                    EventType.END_ELEMENT -> {
                        val t = buf.toString().trim()
                        val d = dive
                        val openSite = site
                        val openGas = gas
                        val openSample = sample
                        val openEvent = event
                        when {
                            // containers close first so their children have been consumed
                            reader.localName == "gear" -> inGear = false
                            reader.localName == "site" -> { d?.site = openSite?.build(); site = null }
                            reader.localName == "gas" -> { openGas?.let { d?.gases?.add(it) }; gas = null }
                            reader.localName == "sample" -> { openSample?.let { d?.samples?.add(it) }; sample = null }
                            reader.localName == "event" -> { openEvent?.let { d?.events?.add(it) }; event = null }
                            reader.localName == "dive" -> { d?.let { dives.add(it.build()) }; dive = null }
                            inGear -> {} // ignore gear item fields (name/type/serial/manufacturer)
                            openSite != null -> when (reader.localName) {
                                "country" -> openSite.country = t.ifBlank { null }
                                "location" -> openSite.place = t.ifBlank { null }
                                "name" -> openSite.name = t
                                "lat" -> openSite.lat = t.toDoubleOrNull()
                                "lon" -> openSite.lon = t.toDoubleOrNull()
                            }
                            openGas != null -> when (reader.localName) {
                                "pressureStart" -> openGas.startPressureMbar = FormatUnits.macBarToMbar(t)
                                "pressureEnd" -> openGas.endPressureMbar = FormatUnits.macBarToMbar(t)
                                "oxygen" -> openGas.o2Permille = FormatUnits.macPercentToPermille(t)
                                "helium" -> openGas.hePermille = FormatUnits.macPercentToPermille(t)
                                "tankSize" -> openGas.volumeMl = FormatUnits.macLitresToMl(t)
                                "workingPressure" -> openGas.workingPressureMbar = FormatUnits.macBarToMbar(t)
                            }
                            openSample != null -> when (reader.localName) {
                                "time" -> openSample.time = FormatUnits.macSeconds(t)
                                "depth" -> openSample.depthMm = FormatUnits.macDepthToMm(t)
                                "pressure" -> openSample.pressureMbar = FormatUnits.macBarToMbar(t)
                                "temperature" -> openSample.tempMk = FormatUnits.macCelsiusToMk(t)
                                "ppo2" -> openSample.ppO2Mbar = FormatUnits.macBarToMbar(t)
                                "ndt" -> openSample.ndlSeconds = FormatUnits.macMinutesToSeconds(t)
                            }
                            openEvent != null -> when (reader.localName) {
                                "type" -> openEvent.typeCode = t.toIntOrNull()
                                "time" -> openEvent.time = FormatUnits.macSeconds(t)
                                "detail" -> openEvent.detail = t.ifBlank { null }
                                "name" -> openEvent.name = t
                            }
                            else -> when (reader.localName) {
                                "date" -> d?.epoch = FormatDateTime.epochFromSpaceDateTime(t)
                                "diveNumber" -> d?.number = t.toIntOrNull()
                                "rating" -> d?.rating = t.toIntOrNull()
                                "computer" -> d?.computerModel = t.ifBlank { null }
                                "maxDepth" -> d?.maxDepthMm = FormatUnits.macDepthToMm(t)
                                "averageDepth" -> d?.meanDepthMm = FormatUnits.macDepthToMm(t)
                                "duration" -> d?.durationSeconds = t.toIntOrNull()
                                "tempAir" -> d?.airTempMk = FormatUnits.macCelsiusToMk(t)
                                "tempLow" -> d?.waterTempMk = FormatUnits.macCelsiusToMk(t)
                                "tempHigh" -> if (d?.waterTempMk == null) d?.waterTempMk = FormatUnits.macCelsiusToMk(t)
                                "visibility" -> d?.visibility = t.toIntOrNull()
                                "notes" -> d?.notes = notesOrNull(t)
                                "buddy" -> if (t.isNotBlank()) d?.buddies?.add(t)
                                "tag" -> if (t.isNotBlank()) d?.tags?.add(t)
                            }
                        }
                    }

                    else -> {}
                }
            }
        } catch (e: Exception) {
            throw FormatException("Malformed MacDive XML", e)
        }
        return DiveLog(dives)
    }

    /**
     * MacDive seeds every dive's notes with an empty section template ("Summary:",
     * "Environment:", "Gas:", "Gear:", "Issues:"). Treat a note that is only such bare
     * headers (and blank lines) as no note; keep it once any line carries real content.
     */
    private fun notesOrNull(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val hasContent = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { !it.endsWith(":") }
        return if (hasContent) trimmed else null
    }

    private fun eventName(e: Event): String = when (e.type) {
        DiveEventType.GAS_SWITCH -> "Switched to gas: ${gasDescriptor(e.value)}"
        DiveEventType.BOOKMARK -> "User Bookmark"
        DiveEventType.ASCENT_RATE -> "Ascent Rate Warning"
        DiveEventType.DECO -> "Deco"
        DiveEventType.SURFACE -> "Surface"
        DiveEventType.WARNING -> "Warning"
        DiveEventType.OTHER -> "Event"
    }

    private fun eventTypeCode(type: DiveEventType): Int = when (type) {
        DiveEventType.GAS_SWITCH -> 10
        DiveEventType.BOOKMARK -> 28
        DiveEventType.ASCENT_RATE -> 2
        DiveEventType.DECO -> 8
        DiveEventType.SURFACE -> 1
        DiveEventType.WARNING -> 20
        DiveEventType.OTHER -> 0
    }

    private fun gasDescriptor(value: Long?): String {
        val v = value ?: 0L
        val o2 = ((v shr 8) and 0xFF).toInt()
        val he = (v and 0xFF).toInt()
        return when {
            he > 0 -> "Trimix $o2/$he"
            o2 == 21 -> "Air"
            else -> "EAN$o2"
        }
    }

    private fun eventFrom(b: EventBuilder): Event {
        val name = b.name ?: ""
        val lower = name.lowercase()
        val type = when {
            lower.startsWith("switched to gas") -> DiveEventType.GAS_SWITCH
            name == "User Bookmark" -> DiveEventType.BOOKMARK
            lower.contains("ascent rate") -> DiveEventType.ASCENT_RATE
            name == "Surface" -> DiveEventType.SURFACE
            lower.contains("deco") || lower.contains("deep stop") || lower.contains("safety stop") -> DiveEventType.DECO
            lower.contains("warning") || lower.contains("high") || lower.contains("alarm") ||
                lower.contains("error") || lower.contains("broken") || lower.contains("floor") ||
                lower.contains("olf") || lower.contains("attention") -> DiveEventType.WARNING
            else -> DiveEventType.OTHER
        }
        val value = if (type == DiveEventType.GAS_SWITCH) parseGasValue(name) else null
        return Event(timeOffsetSeconds = b.time ?: 0, type = type, value = value)
    }

    private fun parseGasValue(name: String): Long {
        val descriptor = name.substringAfter(":", name).trim()
        var o2 = 21
        var he = 0
        when {
            descriptor.equals("Air", ignoreCase = true) -> { o2 = 21; he = 0 }
            descriptor.startsWith("Trimix", ignoreCase = true) -> {
                val nums = descriptor.removePrefix("Trimix").removePrefix("trimix").trim().split("/")
                o2 = nums.getOrNull(0)?.trim()?.toIntOrNull() ?: 21
                he = nums.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            }
            descriptor.startsWith("EAN", ignoreCase = true) ->
                o2 = descriptor.drop(3).trim().toIntOrNull() ?: 21
            else -> descriptor.toIntOrNull()?.let { o2 = it }
        }
        return ((o2 shl 8) or he).toLong()
    }

    private class Gas(
        val o2Permille: Int?,
        val hePermille: Int?,
        val volumeMl: Int?,
        val workingPressureMbar: Int?,
        val startPressureMbar: Int?,
        val endPressureMbar: Int?,
    )

    private class SiteBuilder(
        var name: String = "",
        var country: String? = null,
        var place: String? = null,
        var lat: Double? = null,
        var lon: Double? = null,
    ) {
        fun build() = SiteRef(name, country, place, lat, lon)
    }

    private class GasBuilder(
        var o2Permille: Int? = null,
        var hePermille: Int? = null,
        var volumeMl: Int? = null,
        var workingPressureMbar: Int? = null,
        var startPressureMbar: Int? = null,
        var endPressureMbar: Int? = null,
    )

    private class SampleBuilder(
        var time: Int? = null,
        var depthMm: Int? = null,
        var pressureMbar: Int? = null,
        var tempMk: Int? = null,
        var ppO2Mbar: Int? = null,
        var ndlSeconds: Int? = null,
    ) {
        fun build() = Sample(
            timeOffsetSeconds = time ?: 0,
            depthMm = depthMm,
            temperatureMk = tempMk,
            ppO2Mbar = ppO2Mbar,
            ndlSeconds = ndlSeconds,
            tankPressuresMbar = pressureMbar?.let { mapOf(0 to it) } ?: emptyMap(),
        )
    }

    private class EventBuilder(
        var typeCode: Int? = null,
        var time: Int? = null,
        var detail: String? = null,
        var name: String? = null,
    )

    private inner class DiveBuilder(
        var number: Int? = null,
        var epoch: Long = 0,
        var durationSeconds: Int? = null,
        var maxDepthMm: Int? = null,
        var meanDepthMm: Int? = null,
        var waterTempMk: Int? = null,
        var airTempMk: Int? = null,
        var notes: String? = null,
        var rating: Int? = null,
        var visibility: Int? = null,
        var computerModel: String? = null,
        var site: SiteRef? = null,
        val buddies: MutableList<String> = mutableListOf(),
        val tags: MutableList<String> = mutableListOf(),
        val gases: MutableList<GasBuilder> = mutableListOf(),
        val samples: MutableList<SampleBuilder> = mutableListOf(),
        val events: MutableList<EventBuilder> = mutableListOf(),
    ) {
        fun build(): DiveEntry {
            val tanks = gases.mapIndexed { i, g ->
                TankEntry(
                    index = i,
                    volumeMl = g.volumeMl,
                    workingPressureMbar = g.workingPressureMbar,
                    startPressureMbar = g.startPressureMbar,
                    endPressureMbar = g.endPressureMbar,
                    o2Permille = g.o2Permille,
                    hePermille = g.hePermille,
                )
            }
            val gasMixes = gases.filter { it.o2Permille != null }
                .map { GasMix(o2Permille = it.o2Permille ?: 0, hePermille = it.hePermille ?: 0) }
                .distinct()
            val builtSamples = samples.map { it.build() }
            val builtEvents = events.map { eventFrom(it) }
            val computers = if (builtSamples.isNotEmpty() || builtEvents.isNotEmpty() || computerModel != null) {
                listOf(
                    ComputerEntry(
                        model = computerModel,
                        maxDepthMm = maxDepthMm,
                        meanDepthMm = meanDepthMm,
                        waterTempMk = waterTempMk,
                        airTempMk = airTempMk,
                        samples = builtSamples,
                        events = builtEvents,
                    ),
                )
            } else {
                emptyList()
            }
            return DiveEntry(
                number = number,
                startEpochSeconds = epoch,
                utcOffsetSeconds = 0,
                durationSeconds = durationSeconds ?: 0,
                maxDepthMm = maxDepthMm,
                meanDepthMm = meanDepthMm,
                waterTempMk = waterTempMk,
                airTempMk = airTempMk,
                notes = notes,
                rating = rating,
                visibility = visibility,
                site = site,
                buddies = buddies,
                tags = tags,
                gasMixes = gasMixes,
                tanks = tanks,
                computers = computers,
            )
        }
    }

    private companion object {
        const val NS = ""
        const val SCHEMA = "2.2.0"
        const val DOCTYPE_URL = "http://www.mac-dive.com/macdive_logbook.dtd"
    }
}
