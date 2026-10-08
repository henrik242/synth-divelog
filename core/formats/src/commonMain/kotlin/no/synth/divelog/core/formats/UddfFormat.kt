package no.synth.divelog.core.formats

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.xmlStreaming
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType as DiveEventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.Sample
import kotlin.math.roundToInt

/**
 * Reads and writes UDDF (Universal Dive Data Format). UDDF carries one profile
 * per dive, so on export a dive's primary computer is used, falling back to a
 * secondary computer that has a profile. Units are SI: metres, seconds, Kelvin,
 * Pascal, gas fractions 0..1; CNS is a percentage and ratings run 1..10.
 */
class UddfFormat : DiveFormat {
    override val id: String = "uddf"
    override val displayName: String = "UDDF"

    /** The computer whose profile is exported: primary, else the first with samples. */
    private fun exportComputer(dive: DiveEntry): ComputerEntry? =
        dive.computers.firstOrNull { it.samples.isNotEmpty() } ?: dive.computers.firstOrNull()

    // --- Writing ---

    override fun write(log: DiveLog): String {
        val out = StringBuilder()
        val w: XmlWriter = KtXmlWriter(out, isRepairNamespaces = false)
        w.startDocument("1.0", "UTF-8", null)
        w.startTag(NS, "uddf", "")
        w.namespaceAttr("", NS)
        w.attribute(null, "version", "", "3.2.1")

        w.startTag(NS, "generator", "")
        text(w, "name", "synth-divelog")
        w.endTag(NS, "generator", "")

        // Gas mixes referenced by any dive, tank or gas switch.
        val mixIds = LinkedHashMap<Pair<Int, Int>, String>()
        fun mixId(o2: Int, he: Int) = mixIds.getOrPut(o2 to he) { "mix${mixIds.size + 1}" }
        for (dive in log.dives) {
            dive.gasMixes.forEach { mixId(it.o2Permille, it.hePermille) }
            dive.tanks.forEach { t -> t.o2Permille?.let { mixId(it, t.hePermille ?: 0) } }
            exportComputer(dive)?.events?.filter { it.type == DiveEventType.GAS_SWITCH }?.forEach {
                val (o2, he) = gasOf(it)
                mixId(o2, he)
            }
        }
        if (mixIds.isNotEmpty()) {
            w.startTag(NS, "gasdefinitions", "")
            mixIds.forEach { (gas, id) ->
                w.startTag(NS, "mix", "")
                w.attribute(null, "id", "", id)
                text(w, "o2", FormatUnits.siFraction(gas.first))
                text(w, "he", FormatUnits.siFraction(gas.second))
                w.endTag(NS, "mix", "")
            }
            w.endTag(NS, "gasdefinitions", "")
        }

        val buddyIds = log.dives.flatMap { it.buddies }.distinct().withIndex()
            .associate { (i, name) -> name to "buddy${i + 1}" }
        // Dive computers are the owner's equipment; each dive links the one it was logged on.
        val computerIds = log.dives.mapNotNull { exportComputer(it)?.takeIf { c -> c.model != null } }
            .map { it.model to it.serial }.distinct().withIndex()
            .associate { (i, key) -> key to "computer${i + 1}" }
        if (buddyIds.isNotEmpty() || computerIds.isNotEmpty()) {
            w.startTag(NS, "diver", "")
            if (computerIds.isNotEmpty()) {
                w.startTag(NS, "owner", "")
                w.attribute(null, "id", "", "owner")
                w.startTag(NS, "equipment", "")
                computerIds.forEach { (key, id) ->
                    w.startTag(NS, "divecomputer", "")
                    w.attribute(null, "id", "", id)
                    key.first?.let { text(w, "name", it) }
                    key.second?.let { text(w, "serialnumber", it) }
                    w.endTag(NS, "divecomputer", "")
                }
                w.endTag(NS, "equipment", "")
                w.endTag(NS, "owner", "")
            }
            buddyIds.forEach { (name, id) ->
                w.startTag(NS, "buddy", "")
                w.attribute(null, "id", "", id)
                w.startTag(NS, "personal", "")
                // First word as the first name, the rest as the last name.
                val first = name.substringBefore(' ')
                text(w, "firstname", first)
                name.substringAfter(' ', "").takeIf { it.isNotEmpty() }?.let { text(w, "lastname", it) }
                w.endTag(NS, "personal", "")
                w.endTag(NS, "buddy", "")
            }
            w.endTag(NS, "diver", "")
        }

        val siteIds = log.dives.mapNotNull { it.site }.distinct().withIndex()
            .associate { (i, site) -> site to "site${i + 1}" }
        if (siteIds.isNotEmpty()) {
            w.startTag(NS, "divesite", "")
            siteIds.forEach { (site, id) ->
                w.startTag(NS, "site", "")
                w.attribute(null, "id", "", id)
                text(w, "name", site.name)
                val hasPosition = site.latitude != null && site.longitude != null
                if (site.country != null || site.place != null || hasPosition) {
                    w.startTag(NS, "geography", "")
                    site.country?.let {
                        w.startTag(NS, "address", "")
                        text(w, "country", it)
                        w.endTag(NS, "address", "")
                    }
                    site.place?.let { text(w, "location", it) }
                    if (hasPosition) {
                        text(w, "latitude", site.latitude.toString())
                        text(w, "longitude", site.longitude.toString())
                    }
                    w.endTag(NS, "geography", "")
                }
                w.endTag(NS, "site", "")
            }
            w.endTag(NS, "divesite", "")
        }

        w.startTag(NS, "profiledata", "")
        w.startTag(NS, "repetitiongroup", "")
        log.dives.forEachIndexed { i, dive ->
            val computerId = exportComputer(dive)?.let { computerIds[it.model to it.serial] }
            writeDive(w, dive, "dive${i + 1}", dive.site?.let { siteIds[it] }, buddyIds, computerId, ::mixId)
        }
        w.endTag(NS, "repetitiongroup", "")
        w.endTag(NS, "profiledata", "")

        w.endTag(NS, "uddf", "")
        w.close()
        return out.toString()
    }

    private fun writeDive(
        w: XmlWriter,
        dive: DiveEntry,
        diveId: String,
        siteId: String?,
        buddyIds: Map<String, String>,
        computerId: String?,
        mixId: (Int, Int) -> String,
    ) {
        val computer = exportComputer(dive)
        w.startTag(NS, "dive", "")
        w.attribute(null, "id", "", diveId)

        w.startTag(NS, "informationbeforedive", "")
        text(w, "datetime", FormatDateTime.isoDateTime(dive.startEpochSeconds, dive.utcOffsetSeconds))
        dive.number?.let { text(w, "divenumber", it.toString()) }
        dive.airTempMk?.let { text(w, "airtemperature", FormatUnits.siKelvin(it)) }
        siteId?.let { linkRef(w, it) }
        dive.buddies.forEach { b -> buddyIds[b]?.let { linkRef(w, it) } }
        w.endTag(NS, "informationbeforedive", "")

        // Waypoint tank pressures refer to their tank by its tankdata id.
        val tankIds = dive.tanks.withIndex().associate { (i, t) -> t.index to "${diveId}_tank${i + 1}" }
        dive.tanks.forEachIndexed { i, tank ->
            w.startTag(NS, "tankdata", "")
            w.attribute(null, "id", "", "${diveId}_tank${i + 1}")
            tank.o2Permille?.let { linkRef(w, mixId(it, tank.hePermille ?: 0)) }
            tank.volumeMl?.let { text(w, "tankvolume", FormatUnits.siCubicMetres(it)) }
            tank.startPressureMbar?.let { text(w, "tankpressurebegin", FormatUnits.siPascal(it)) }
            tank.endPressureMbar?.let { text(w, "tankpressureend", FormatUnits.siPascal(it)) }
            w.endTag(NS, "tankdata", "")
        }

        if (computer != null && computer.samples.isNotEmpty()) {
            // Each event goes on the first waypoint at or after its time.
            val eventsAt = HashMap<Int, MutableList<Event>>()
            for (e in computer.events) {
                val at = computer.samples.indexOfFirst { it.timeOffsetSeconds >= e.timeOffsetSeconds }
                if (at >= 0) eventsAt.getOrPut(at) { mutableListOf() }.add(e)
            }
            w.startTag(NS, "samples", "")
            computer.samples.forEachIndexed { i, s ->
                val events = eventsAt[i].orEmpty()
                w.startTag(NS, "waypoint", "")
                for (e in events) {
                    val alarm = alarmName(e.type) ?: continue
                    w.startTag(NS, "alarm", "")
                    e.value?.let { w.attribute(null, "level", "", it.toString()) }
                    w.text(alarm)
                    w.endTag(NS, "alarm", "")
                }
                text(w, "depth", FormatUnits.siMetres(s.depthMm ?: 0))
                text(w, "divetime", s.timeOffsetSeconds.toString())
                s.ppO2Mbar?.let { text(w, "calculatedpo2", FormatUnits.siPascal(it)) }
                s.cnsPermille?.let { text(w, "cns", (it / 10.0).toString()) }
                val stopDepthMm = s.stopDepthMm
                val stopTimeSeconds = s.stopTimeSeconds
                if (stopDepthMm != null && stopTimeSeconds != null) {
                    w.startTag(NS, "decostop", "")
                    w.attribute(null, "kind", "", "mandatory")
                    w.attribute(null, "decodepth", "", FormatUnits.siMetres(stopDepthMm))
                    w.attribute(null, "duration", "", stopTimeSeconds.toString())
                    w.endTag(NS, "decostop", "")
                }
                s.ndlSeconds?.let { text(w, "nodecotime", it.toString()) }
                for (e in events.filter { it.type == DiveEventType.GAS_SWITCH }) {
                    val (o2, he) = gasOf(e)
                    w.startTag(NS, "switchmix", "")
                    w.attribute(null, "ref", "", mixId(o2, he))
                    w.endTag(NS, "switchmix", "")
                }
                for ((tank, mbar) in s.tankPressuresMbar.entries.sortedBy { it.key }) {
                    w.startTag(NS, "tankpressure", "")
                    tankIds[tank]?.let { w.attribute(null, "ref", "", it) }
                    w.text(FormatUnits.siPascal(mbar))
                    w.endTag(NS, "tankpressure", "")
                }
                s.temperatureMk?.let { text(w, "temperature", FormatUnits.siKelvin(it)) }
                w.endTag(NS, "waypoint", "")
            }
            w.endTag(NS, "samples", "")
        }

        w.startTag(NS, "informationafterdive", "")
        (computer?.maxDepthMm ?: dive.maxDepthMm)?.let { text(w, "greatestdepth", FormatUnits.siMetres(it)) }
        (computer?.meanDepthMm ?: dive.meanDepthMm)?.let { text(w, "averagedepth", FormatUnits.siMetres(it)) }
        text(w, "diveduration", dive.durationSeconds.toString())
        (computer?.waterTempMk ?: dive.waterTempMk)?.let { text(w, "lowesttemperature", FormatUnits.siKelvin(it)) }
        // UDDF rates 1..10; the logbook rates 0..5 stars.
        dive.rating?.takeIf { it > 0 }?.let {
            w.startTag(NS, "rating", "")
            text(w, "ratingvalue", (it * 2).toString())
            w.endTag(NS, "rating", "")
        }
        dive.visibility?.let { text(w, "visibility", FormatUnits.siMetres(it)) }
        dive.notes?.let {
            w.startTag(NS, "notes", "")
            it.lines().forEach { line -> text(w, "para", line) }
            w.endTag(NS, "notes", "")
        }
        computerId?.let {
            w.startTag(NS, "equipmentused", "")
            linkRef(w, it)
            w.endTag(NS, "equipmentused", "")
        }
        w.endTag(NS, "informationafterdive", "")

        w.endTag(NS, "dive", "")
    }

    private fun gasOf(event: Event): Pair<Int, Int> {
        val v = event.value ?: 0L
        return GasSwitch.o2Percent(v) * 10 to GasSwitch.hePercent(v) * 10 // permille
    }

    /** The UDDF alarm for an event; bookmarks and unclassified events have none. */
    private fun alarmName(type: DiveEventType): String? = when (type) {
        DiveEventType.ASCENT_RATE -> "ascent"
        DiveEventType.DECO -> "deco"
        DiveEventType.SURFACE -> "surface"
        DiveEventType.WARNING -> "error"
        DiveEventType.GAS_SWITCH, DiveEventType.BOOKMARK, DiveEventType.OTHER -> null
    }

    private fun linkRef(w: XmlWriter, ref: String) {
        w.startTag(NS, "link", "")
        w.attribute(null, "ref", "", ref)
        w.endTag(NS, "link", "")
    }

    private fun text(w: XmlWriter, name: String, value: String) {
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

        val mixes = mutableMapOf<String, GasMix>()
        val sites = mutableMapOf<String, SiteRef>()
        val buddies = mutableMapOf<String, String>()
        val dives = mutableListOf<DiveEntry>()

        var mix: MixBuilder? = null
        var buddy: BuddyBuilder? = null
        var site: SiteBuilder? = null
        var dive: DiveB? = null
        var waypoint: WaypointB? = null
        var tank: TankB? = null
        var alarmLevel: String? = null
        var tankPressureRef: String? = null
        // The owner's dive computers by id, and the one being read: only its direct children
        // count, as its manufacturer and purchase details carry names of their own.
        val computers = mutableMapOf<String, ComputerB>()
        var computer: ComputerB? = null
        var depth = 0
        var computerDepth = -1
        var siteDepth = -1
        var buf = StringBuilder()

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> {
                        depth++
                        buf = StringBuilder()
                        when (reader.localName) {
                            "mix" -> mix = MixBuilder(attr(reader, "id"))
                            "buddy" -> buddy = BuddyBuilder(attr(reader, "id"))
                            "site" -> {
                                site = SiteBuilder(attr(reader, "id"), attr(reader, "name"))
                                siteDepth = depth
                            }
                            "dive" -> dive = DiveB()
                            "waypoint" -> waypoint = WaypointB()
                            "tankdata" -> tank = TankB(attr(reader, "id"))
                            "divecomputer" -> {
                                computer = ComputerB(attr(reader, "id"))
                                computerDepth = depth
                            }
                            "link" -> attr(reader, "ref")?.let { ref ->
                                val open = tank
                                if (open != null) open.mixRef = ref else dive?.links?.add(ref)
                            }
                            "switchmix" -> attr(reader, "ref")?.let { waypoint?.switchMixes?.add(it) }
                            "decostop" -> waypoint?.let { wp ->
                                attr(reader, "decodepth")?.let { wp.stopDepthMm = FormatUnits.siMetresToMm(it) }
                                attr(reader, "duration")?.let { wp.stopTime = seconds(it) }
                            }
                            "alarm" -> alarmLevel = attr(reader, "level")
                            // The spec's attribute is "ref"; its examples write "tankref".
                            "tankpressure" -> tankPressureRef = attr(reader, "ref") ?: attr(reader, "tankref")
                        }
                    }

                    EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF -> buf.append(reader.text)

                    EventType.END_ELEMENT -> {
                        val t = buf.toString().trim()
                        when (reader.localName) {
                            "o2" -> mix?.o2 = FormatUnits.siFractionToPermille(t)
                            "he" -> mix?.he = FormatUnits.siFractionToPermille(t)
                            "mix" -> mix?.let { b -> b.id?.let { mixes[it] = GasMix(o2Permille = b.o2 ?: 0, hePermille = b.he ?: 0) } }
                            "firstname" -> buddy?.first = t.ifEmpty { null }
                            "middlename" -> buddy?.middle = t.ifEmpty { null }
                            "lastname" -> buddy?.last = t.ifEmpty { null }
                            "name" -> when (depth) {
                                computerDepth + 1 -> computer?.name = t.ifEmpty { null }
                                siteDepth + 1 -> site?.name = t.ifEmpty { null }
                            }
                            "location" -> site?.place = t.ifEmpty { null }
                            "country" -> site?.country = t.ifEmpty { null }
                            "serialnumber" -> if (depth == computerDepth + 1) computer?.serial = t.ifEmpty { null }
                            "divecomputer" -> {
                                computer?.let { c -> c.id?.let { computers[it] = c } }
                                computer = null
                                computerDepth = -1
                            }
                            "buddy" -> buddy?.let { b -> b.id?.let { buddies[it] = b.name() } }
                            "latitude" -> site?.lat = t.toDoubleOrNull()
                            "longitude" -> site?.lon = t.toDoubleOrNull()
                            "site" -> {
                                site?.let { b -> b.id?.let { sites[it] = parseSite(b) } }
                                site = null
                                siteDepth = -1
                            }
                            "datetime" -> dive?.let { d -> FormatDateTime.fromIso(t).let { (epoch, offset) -> d.epoch = epoch; d.utcOffset = offset } }
                            "divenumber" -> dive?.number = t.toIntOrNull()
                            "airtemperature" -> dive?.airTempMk = FormatUnits.siKelvinToMk(t)
                            "depth" -> waypoint?.depthMm = FormatUnits.siMetresToMm(t)
                            "divetime" -> waypoint?.time = seconds(t)
                            "temperature" -> waypoint?.tempMk = FormatUnits.siKelvinToMk(t)
                            "calculatedpo2" -> waypoint?.calculatedPo2Mbar = FormatUnits.siPascalToMbar(t)
                            "measuredpo2" -> waypoint?.let { if (it.measuredPo2Mbar == null) it.measuredPo2Mbar = FormatUnits.siPascalToMbar(t) }
                            "cns" -> waypoint?.cnsPermille = FormatUnits.leadingNumber(t)?.let { (it * 10).roundToInt() }
                            "nodecotime" -> waypoint?.ndl = seconds(t)
                            "alarm" -> waypoint?.alarms?.add(t to alarmLevel)
                            "tankpressure" -> waypoint?.let { wp ->
                                FormatUnits.siPascalToMbar(t)?.takeIf { it > 0 }?.let { wp.pressures.add(tankPressureRef to it) }
                            }
                            // Earlier exports of this app wrote the stop as child elements.
                            "decodepth" -> waypoint?.stopDepthMm = FormatUnits.siMetresToMm(t)
                            "duration" -> waypoint?.stopTime = seconds(t)
                            "waypoint" -> { waypoint?.let { dive?.waypoints?.add(it) }; waypoint = null }
                            "tankvolume" -> tank?.volumeMl = FormatUnits.siCubicMetresToMl(t)?.takeIf { it > 0 }
                            "tankpressurebegin" -> tank?.startMbar = FormatUnits.siPascalToMbar(t)?.takeIf { it > 0 }
                            "tankpressureend" -> tank?.endMbar = FormatUnits.siPascalToMbar(t)?.takeIf { it > 0 }
                            "tankdata" -> { tank?.let { dive?.tanks?.add(it) }; tank = null }
                            "greatestdepth" -> dive?.maxDepthMm = FormatUnits.siMetresToMm(t)
                            "averagedepth" -> dive?.meanDepthMm = FormatUnits.siMetresToMm(t)
                            "diveduration" -> dive?.duration = seconds(t)
                            "lowesttemperature" -> dive?.waterTempMk = FormatUnits.siKelvinToMk(t)
                            // UDDF rates 1..10; the logbook rates 0..5 stars.
                            "ratingvalue" -> dive?.rating = FormatUnits.leadingNumber(t)?.let { ((it + 1) / 2).toInt() }
                            "visibility" -> dive?.visibilityMm = FormatUnits.siMetresToMm(t)
                            // One paragraph per line.
                            "para" -> dive?.let { d -> d.notes = d.notes?.let { "$it\n$t" } ?: t }
                            "dive" -> { dive?.let { dives.add(it.build(mixes, sites, buddies, computers)) }; dive = null }
                        }
                        depth--
                    }

                    else -> {}
                }
            }
        } catch (e: Exception) {
            throw FormatException("Malformed UDDF", e)
        }
        return DiveLog(dives)
    }

    private fun attr(reader: XmlReader, name: String): String? = reader.getAttributeValue(null, name)

    /**
     * A site from its `<name>` and `<geography>` (`<location>`, `<address><country>`). Files
     * from earlier versions of this app put "country / place / name" in a name attribute.
     */
    private fun parseSite(b: SiteBuilder): SiteRef {
        val legacy = b.legacyName?.split(" / ").orEmpty()
        return when {
            b.name != null || legacy.size < 2 -> SiteRef(b.name ?: b.legacyName ?: "", b.country, b.place, b.lat, b.lon)
            legacy.size == 2 -> SiteRef(legacy[1], legacy[0], null, b.lat, b.lon)
            else -> SiteRef(legacy[2], legacy[0], legacy[1], b.lat, b.lon)
        }
    }

    private class MixBuilder(val id: String?, var o2: Int? = null, var he: Int? = null)
    private class BuddyBuilder(val id: String?, var first: String? = null, var middle: String? = null, var last: String? = null) {
        fun name() = listOfNotNull(first, middle, last).joinToString(" ")
    }
    private class SiteBuilder(
        val id: String?,
        val legacyName: String?,
        var name: String? = null,
        var place: String? = null,
        var country: String? = null,
        var lat: Double? = null,
        var lon: Double? = null,
    )
    /** Seconds written as "2130" or "2130.0". */
    private fun seconds(text: String): Int? = FormatUnits.leadingNumber(text)?.roundToInt()

    private class ComputerB(val id: String?, var name: String? = null, var serial: String? = null)

    private class TankB(
        val id: String?,
        var mixRef: String? = null,
        var volumeMl: Int? = null,
        var startMbar: Int? = null,
        var endMbar: Int? = null,
    )

    private class WaypointB(
        var depthMm: Int? = null,
        var time: Int? = null,
        var tempMk: Int? = null,
        var calculatedPo2Mbar: Int? = null,
        var measuredPo2Mbar: Int? = null,
        var cnsPermille: Int? = null,
        var ndl: Int? = null,
        var stopDepthMm: Int? = null,
        var stopTime: Int? = null,
        val switchMixes: MutableList<String> = mutableListOf(),
        val alarms: MutableList<Pair<String, String?>> = mutableListOf(),
        val pressures: MutableList<Pair<String?, Int>> = mutableListOf(),
    )

    private class DiveB(
        var number: Int? = null,
        var epoch: Long = 0,
        var utcOffset: Int = 0,
        var duration: Int? = null,
        var maxDepthMm: Int? = null,
        var meanDepthMm: Int? = null,
        var waterTempMk: Int? = null,
        var airTempMk: Int? = null,
        var rating: Int? = null,
        var visibilityMm: Int? = null,
        var notes: String? = null,
        val links: MutableList<String> = mutableListOf(),
        val waypoints: MutableList<WaypointB> = mutableListOf(),
        val tanks: MutableList<TankB> = mutableListOf(),
    ) {
        fun build(
            mixes: Map<String, GasMix>,
            sites: Map<String, SiteRef>,
            buddies: Map<String, String>,
            computers: Map<String, ComputerB>,
        ): DiveEntry {
            val site = links.firstNotNullOfOrNull { sites[it] }
            val used = links.firstNotNullOfOrNull { computers[it] }
            val buddyNames = links.mapNotNull { buddies[it] }
            // A pressure without a tank reference belongs to the first tank.
            val tankIndex = tanks.withIndex().mapNotNull { (i, t) -> t.id?.let { it to i } }.toMap()
            val samples = waypoints.map { wp ->
                Sample(
                    timeOffsetSeconds = wp.time ?: 0,
                    depthMm = wp.depthMm,
                    temperatureMk = wp.tempMk,
                    ppO2Mbar = wp.calculatedPo2Mbar ?: wp.measuredPo2Mbar,
                    ndlSeconds = wp.ndl,
                    stopDepthMm = wp.stopDepthMm,
                    stopTimeSeconds = wp.stopTime,
                    cnsPermille = wp.cnsPermille,
                    tankPressuresMbar = wp.pressures.associate { (ref, mbar) -> (ref?.let { tankIndex[it] } ?: 0) to mbar },
                )
            }
            val events = waypoints.flatMap { wp ->
                val time = wp.time ?: 0
                wp.alarms.map { (name, level) ->
                    Event(timeOffsetSeconds = time, type = alarmType(name), value = level?.let(FormatUnits::leadingNumber)?.toLong())
                } + wp.switchMixes.mapNotNull { ref ->
                    val gas = mixes[ref] ?: return@mapNotNull null
                    Event(timeOffsetSeconds = time, type = DiveEventType.GAS_SWITCH, value = GasSwitch.value(gas.o2Permille / 10, gas.hePermille / 10))
                }
            }
            val computer = ComputerEntry(
                model = used?.name,
                serial = used?.serial,
                maxDepthMm = maxDepthMm,
                meanDepthMm = meanDepthMm,
                waterTempMk = waterTempMk,
                airTempMk = airTempMk,
                samples = samples,
                events = events,
            )
            return DiveEntry(
                number = number,
                startEpochSeconds = epoch,
                utcOffsetSeconds = utcOffset,
                // Some writers leave the duration out; the profile still spans the dive.
                durationSeconds = duration ?: waypoints.mapNotNull { it.time }.maxOrNull() ?: 0,
                maxDepthMm = maxDepthMm,
                meanDepthMm = meanDepthMm,
                waterTempMk = waterTempMk,
                airTempMk = airTempMk,
                notes = notes?.trim()?.ifEmpty { null },
                rating = rating,
                visibility = visibilityMm,
                site = site,
                buddies = buddyNames,
                tanks = tanks.mapIndexed { i, t ->
                    val gas = t.mixRef?.let { mixes[it] }
                    TankEntry(
                        index = i,
                        volumeMl = t.volumeMl,
                        startPressureMbar = t.startMbar,
                        endPressureMbar = t.endMbar,
                        o2Permille = gas?.o2Permille,
                        hePermille = gas?.hePermille,
                    )
                },
                // The tanks' gases, then mixes switched to but carried by no tank.
                gasMixes = (tanks.mapNotNull { t -> t.mixRef?.let { mixes[it] } } + waypoints.flatMap { wp -> wp.switchMixes.mapNotNull { mixes[it] } }).distinct(),
                // A dive without a profile or a linked computer was logged by hand.
                computers = if (samples.isNotEmpty() || used != null) listOf(computer) else emptyList(),
            )
        }

        private fun alarmType(name: String): DiveEventType = when (name.trim()) {
            "ascent" -> DiveEventType.ASCENT_RATE
            "deco" -> DiveEventType.DECO
            "surface" -> DiveEventType.SURFACE
            else -> DiveEventType.WARNING
        }
    }

    private companion object {
        /** Elements are in the UDDF namespace; attributes in none. */
        const val NS = "http://www.streit.cc/uddf/3.2/"
    }
}
