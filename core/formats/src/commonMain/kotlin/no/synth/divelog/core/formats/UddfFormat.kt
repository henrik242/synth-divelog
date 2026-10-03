package no.synth.divelog.core.formats

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.xmlStreaming
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType as DiveEventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Sample

/**
 * Reads and writes UDDF (Universal Dive Data Format). UDDF carries one profile
 * per dive, so on export a dive's primary computer is used, falling back to a
 * secondary computer that has a profile. Units are SI: metres, seconds, Kelvin,
 * gas fractions 0..1.
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
        w.startTag(NS, "uddf", "")
        w.attribute(NS, "version", "", "3.2.1")

        w.startTag(NS, "generator", "")
        text(w, "name", "synth-divelog")
        w.endTag(NS, "generator", "")

        // Gas mixes referenced by any dive or gas switch.
        val mixIds = LinkedHashMap<Pair<Int, Int>, String>()
        fun mixId(o2: Int, he: Int) = mixIds.getOrPut(o2 to he) { "mix${mixIds.size + 1}" }
        for (dive in log.dives) {
            dive.gasMixes.forEach { mixId(it.o2Permille, it.hePermille) }
            exportComputer(dive)?.events?.filter { it.type == DiveEventType.GAS_SWITCH }?.forEach {
                val (o2, he) = gasOf(it)
                mixId(o2, he)
            }
        }
        if (mixIds.isNotEmpty()) {
            w.startTag(NS, "gasdefinitions", "")
            mixIds.forEach { (gas, id) ->
                w.startTag(NS, "mix", "")
                w.attribute(NS, "id", "", id)
                text(w, "o2", FormatUnits.siFraction(gas.first))
                text(w, "he", FormatUnits.siFraction(gas.second))
                w.endTag(NS, "mix", "")
            }
            w.endTag(NS, "gasdefinitions", "")
        }

        val buddyIds = log.dives.flatMap { it.buddies }.distinct().withIndex()
            .associate { (i, name) -> name to "buddy${i + 1}" }
        if (buddyIds.isNotEmpty()) {
            w.startTag(NS, "diver", "")
            buddyIds.forEach { (name, id) ->
                w.startTag(NS, "buddy", "")
                w.attribute(NS, "id", "", id)
                w.startTag(NS, "personal", "")
                text(w, "firstname", name)
                w.endTag(NS, "personal", "")
                w.endTag(NS, "buddy", "")
            }
            w.endTag(NS, "diver", "")
        }

        val siteIds = log.dives.mapNotNull { it.site }.distinctBy { it.name }.withIndex()
            .associate { (i, site) -> site.name to ("site${i + 1}" to site) }
        if (siteIds.isNotEmpty()) {
            w.startTag(NS, "divesite", "")
            siteIds.values.forEach { (id, site) ->
                w.startTag(NS, "site", "")
                w.attribute(NS, "id", "", id)
                w.attribute(NS, "name", "", fullSiteName(site))
                if (site.latitude != null && site.longitude != null) {
                    w.startTag(NS, "geography", "")
                    text(w, "latitude", site.latitude.toString())
                    text(w, "longitude", site.longitude.toString())
                    w.endTag(NS, "geography", "")
                }
                w.endTag(NS, "site", "")
            }
            w.endTag(NS, "divesite", "")
        }

        w.startTag(NS, "profiledata", "")
        w.startTag(NS, "repetitiongroup", "")
        for (dive in log.dives) {
            writeDive(w, dive, siteIds[dive.site?.name]?.first, buddyIds, ::mixId)
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
        siteId: String?,
        buddyIds: Map<String, String>,
        mixId: (Int, Int) -> String,
    ) {
        val computer = exportComputer(dive)
        w.startTag(NS, "dive", "")

        w.startTag(NS, "informationbeforedive", "")
        text(w, "datetime", FormatDateTime.isoDateTime(dive.startEpochSeconds, dive.utcOffsetSeconds))
        dive.number?.let { text(w, "divenumber", it.toString()) }
        siteId?.let { linkRef(w, it) }
        dive.buddies.forEach { b -> buddyIds[b]?.let { linkRef(w, it) } }
        w.endTag(NS, "informationbeforedive", "")

        val switches = computer?.events.orEmpty().filter { it.type == DiveEventType.GAS_SWITCH }
            .associateBy { it.timeOffsetSeconds }
        if (computer != null && computer.samples.isNotEmpty()) {
            w.startTag(NS, "samples", "")
            for (s in computer.samples) {
                w.startTag(NS, "waypoint", "")
                text(w, "depth", FormatUnits.siMetres(s.depthMm ?: 0))
                text(w, "divetime", s.timeOffsetSeconds.toString())
                s.temperatureMk?.let { text(w, "temperature", FormatUnits.siKelvin(it)) }
                switches[s.timeOffsetSeconds]?.let { e ->
                    val (o2, he) = gasOf(e)
                    w.startTag(NS, "switchmix", "")
                    w.attribute(NS, "ref", "", mixId(o2, he))
                    w.endTag(NS, "switchmix", "")
                }
                if (s.stopDepthMm != null && s.stopTimeSeconds != null) {
                    w.startTag(NS, "decostop", "")
                    text(w, "decodepth", FormatUnits.siMetres(s.stopDepthMm!!))
                    text(w, "duration", s.stopTimeSeconds.toString())
                    w.endTag(NS, "decostop", "")
                }
                w.endTag(NS, "waypoint", "")
            }
            w.endTag(NS, "samples", "")
        }

        w.startTag(NS, "informationafterdive", "")
        (computer?.maxDepthMm ?: dive.maxDepthMm)?.let { text(w, "greatestdepth", FormatUnits.siMetres(it)) }
        (computer?.meanDepthMm ?: dive.meanDepthMm)?.let { text(w, "averagedepth", FormatUnits.siMetres(it)) }
        text(w, "diveduration", dive.durationSeconds.toString())
        (computer?.waterTempMk ?: dive.waterTempMk)?.let { text(w, "lowesttemperature", FormatUnits.siKelvin(it)) }
        dive.rating?.let {
            w.startTag(NS, "rating", "")
            text(w, "ratingvalue", it.toString())
            w.endTag(NS, "rating", "")
        }
        dive.visibility?.let { text(w, "visibility", FormatUnits.siMetres(it)) }
        dive.notes?.let {
            w.startTag(NS, "notes", "")
            text(w, "para", it)
            w.endTag(NS, "notes", "")
        }
        w.endTag(NS, "informationafterdive", "")

        w.endTag(NS, "dive", "")
    }

    private fun gasOf(event: Event): Pair<Int, Int> {
        val v = event.value ?: 0L
        val o2Percent = ((v shr 8) and 0xFF).toInt()
        val hePercent = (v and 0xFF).toInt()
        return o2Percent * 10 to hePercent * 10 // permille
    }

    private fun linkRef(w: XmlWriter, ref: String) {
        w.startTag(NS, "link", "")
        w.attribute(NS, "ref", "", ref)
        w.endTag(NS, "link", "")
    }

    private fun text(w: XmlWriter, name: String, value: String) {
        w.startTag(NS, name, "")
        w.text(value)
        w.endTag(NS, name, "")
    }

    private fun fullSiteName(site: SiteRef): String =
        listOfNotNull(site.country, site.place, site.name).joinToString(" / ")

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
        var buf = StringBuilder()

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> {
                        buf = StringBuilder()
                        when (reader.localName) {
                            "mix" -> mix = MixBuilder(reader.getAttributeValue(null, "id"))
                            "buddy" -> buddy = BuddyBuilder(reader.getAttributeValue(null, "id"))
                            "site" -> site = SiteBuilder(
                                reader.getAttributeValue(null, "id"),
                                reader.getAttributeValue(null, "name"),
                            )
                            "dive" -> dive = DiveB()
                            "waypoint" -> waypoint = WaypointB()
                            "link" -> reader.getAttributeValue(null, "ref")?.let { dive?.links?.add(it) }
                            "switchmix" -> waypoint?.switchMix = reader.getAttributeValue(null, "ref")
                        }
                    }

                    EventType.TEXT, EventType.CDSECT -> buf.append(reader.text)

                    EventType.END_ELEMENT -> {
                        val t = buf.toString().trim()
                        when (reader.localName) {
                            "o2" -> mix?.o2 = FormatUnits.siFractionToPermille(t)
                            "he" -> mix?.he = FormatUnits.siFractionToPermille(t)
                            "mix" -> mix?.let { b -> b.id?.let { mixes[it] = GasMix(o2Permille = b.o2 ?: 0, hePermille = b.he ?: 0) } }
                            "firstname" -> buddy?.name = t
                            "buddy" -> buddy?.let { b -> b.id?.let { buddies[it] = b.name ?: "" } }
                            "latitude" -> site?.lat = t.toDoubleOrNull()
                            "longitude" -> site?.lon = t.toDoubleOrNull()
                            "site" -> site?.let { b -> b.id?.let { sites[it] = parseSite(b) } }
                            "datetime" -> dive?.epoch = FormatDateTime.epochFromIso(t)
                            "divenumber" -> dive?.number = t.toIntOrNull()
                            "depth" -> waypoint?.depthMm = FormatUnits.siMetresToMm(t)
                            "divetime" -> waypoint?.time = t.toIntOrNull()
                            "temperature" -> waypoint?.tempMk = FormatUnits.siKelvinToMk(t)
                            "decodepth" -> waypoint?.stopDepthMm = FormatUnits.siMetresToMm(t)
                            "duration" -> waypoint?.stopTime = t.toIntOrNull()
                            "waypoint" -> waypoint?.let { dive?.waypoints?.add(it) }
                            "greatestdepth" -> dive?.maxDepthMm = FormatUnits.siMetresToMm(t)
                            "averagedepth" -> dive?.meanDepthMm = FormatUnits.siMetresToMm(t)
                            "diveduration" -> dive?.duration = t.toIntOrNull()
                            "lowesttemperature" -> dive?.waterTempMk = FormatUnits.siKelvinToMk(t)
                            "ratingvalue" -> dive?.rating = t.toIntOrNull()
                            "visibility" -> dive?.visibilityMm = FormatUnits.siMetresToMm(t)
                            "para" -> dive?.notes = t
                            "dive" -> dive?.let { dives.add(it.build(mixes, sites, buddies)) }
                        }
                    }

                    else -> {}
                }
            }
        } catch (e: Exception) {
            throw FormatException("Malformed UDDF", e)
        }
        return DiveLog(dives)
    }

    private fun parseSite(b: SiteBuilder): SiteRef {
        val name = b.name ?: ""
        val parts = name.split(" / ")
        return when (parts.size) {
            3 -> SiteRef(parts[2], parts[0], parts[1], b.lat, b.lon)
            2 -> SiteRef(parts[1], parts[0], null, b.lat, b.lon)
            else -> SiteRef(name, null, null, b.lat, b.lon)
        }
    }

    private class MixBuilder(val id: String?, var o2: Int? = null, var he: Int? = null)
    private class BuddyBuilder(val id: String?, var name: String? = null)
    private class SiteBuilder(val id: String?, val name: String?, var lat: Double? = null, var lon: Double? = null)
    private class WaypointB(
        var depthMm: Int? = null,
        var time: Int? = null,
        var tempMk: Int? = null,
        var switchMix: String? = null,
        var stopDepthMm: Int? = null,
        var stopTime: Int? = null,
    )

    private class DiveB(
        var number: Int? = null,
        var epoch: Long = 0,
        var duration: Int? = null,
        var maxDepthMm: Int? = null,
        var meanDepthMm: Int? = null,
        var waterTempMk: Int? = null,
        var rating: Int? = null,
        var visibilityMm: Int? = null,
        var notes: String? = null,
        val links: MutableList<String> = mutableListOf(),
        val waypoints: MutableList<WaypointB> = mutableListOf(),
    ) {
        fun build(mixes: Map<String, GasMix>, sites: Map<String, SiteRef>, buddies: Map<String, String>): DiveEntry {
            val site = links.firstNotNullOfOrNull { sites[it] }
            val buddyNames = links.mapNotNull { buddies[it] }
            val samples = waypoints.map { wp ->
                Sample(
                    timeOffsetSeconds = wp.time ?: 0,
                    depthMm = wp.depthMm,
                    temperatureMk = wp.tempMk,
                    stopDepthMm = wp.stopDepthMm,
                    stopTimeSeconds = wp.stopTime,
                )
            }
            val events = waypoints.mapNotNull { wp ->
                val ref = wp.switchMix ?: return@mapNotNull null
                val gas = mixes[ref] ?: return@mapNotNull null
                val value = (((gas.o2Permille / 10) shl 8) or (gas.hePermille / 10)).toLong()
                Event(timeOffsetSeconds = wp.time ?: 0, type = DiveEventType.GAS_SWITCH, value = value)
            }
            val computer = ComputerEntry(
                model = null,
                maxDepthMm = maxDepthMm,
                meanDepthMm = meanDepthMm,
                waterTempMk = waterTempMk,
                samples = samples,
                events = events,
            )
            return DiveEntry(
                number = number,
                startEpochSeconds = epoch,
                utcOffsetSeconds = 0,
                durationSeconds = duration ?: 0,
                maxDepthMm = maxDepthMm,
                meanDepthMm = meanDepthMm,
                waterTempMk = waterTempMk,
                notes = notes,
                rating = rating,
                visibility = visibilityMm,
                site = site,
                buddies = buddyNames,
                computers = if (samples.isNotEmpty() || maxDepthMm != null) listOf(computer) else emptyList(),
            )
        }
    }

    private companion object {
        const val NS = ""
    }
}
