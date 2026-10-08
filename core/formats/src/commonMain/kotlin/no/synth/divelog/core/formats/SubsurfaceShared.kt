package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.Sample
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Conventions shared by the Subsurface XML and git-tree formats: the same event names,
 * site taxonomy, device ids and number layout appear in both.
 */
internal object SubsurfaceShared {
    /** Extra-data key holding the dive computer's serial number. */
    const val KEY_SERIAL = "Serial"

    /** Extra-data key holding the dive's offset from UTC, seconds east, written "%+d". */
    const val KEY_UTC_OFFSET = "Time offset from UTC [s]"

    /** Site taxonomy categories ("geo" entries): country and first admin level. */
    const val GEO_COUNTRY = 2
    const val GEO_ADMIN_L1 = 3

    /** Other place categories, read in this order when there is no first admin level. */
    val GEO_PLACE_FALLBACK = listOf(4, 6, 5)

    /** Taxonomy origin for a value the user entered. */
    const val GEO_MANUAL = 2

    /** Event `type` number for [e]; a gas switch to a mix with helium has its own. */
    fun eventTypeNumber(e: Event): Int? = when (e.type) {
        EventType.GAS_SWITCH -> if (GasSwitch.hePercent(e.value ?: 0L) > 0) 25 else 11
        EventType.BOOKMARK -> 8
        EventType.ASCENT_RATE -> 3
        EventType.SURFACE -> 9
        EventType.DECO -> 1
        EventType.WARNING -> 7
        EventType.OTHER -> null
    }

    /** The `value` written for [e]: a gas switch's mix as o2 + (he << 16), in percent. */
    fun eventValue(e: Event): Long? {
        val v = e.value ?: return null
        if (e.type != EventType.GAS_SWITCH) return v
        return GasSwitch.o2Percent(v) + (GasSwitch.hePercent(v).toLong() shl 16)
    }

    fun eventName(type: EventType): String = when (type) {
        EventType.GAS_SWITCH -> "gaschange"
        EventType.BOOKMARK -> "bookmark"
        EventType.ASCENT_RATE -> "ascent"
        EventType.SURFACE -> "surface"
        EventType.DECO -> "deco stop"
        EventType.WARNING -> "violation"
        EventType.OTHER -> "event"
    }

    /** Our event type for a logged event name. "deco" and "warning" are from earlier exports of this app. */
    fun eventType(name: String?): EventType {
        val n = name?.trim()?.lowercase() ?: return EventType.OTHER
        return when {
            n == "gaschange" -> EventType.GAS_SWITCH
            n == "bookmark" -> EventType.BOOKMARK
            n == "ascent" -> EventType.ASCENT_RATE
            n == "surface" -> EventType.SURFACE
            n == "deco" || n == "deco stop" || n == "deepstop" || n.startsWith("safety stop") ||
                n.startsWith("ceiling") -> EventType.DECO
            n == "warning" || n == "violation" || n == "po2" || n == "olf" || n == "below floor" ||
                n == "maxdepth" || n == "divetime" || n == "airtime" || n == "rbt" || n == "rgbm" ||
                n == "workload" || n == "transmitter" || n == "tissue level warning" -> EventType.WARNING
            else -> EventType.OTHER
        }
    }

    /** The 8-hex-digit device id of a computer with serial [serial]: the first four SHA-1 bytes, little-endian. */
    fun deviceId(serial: String): String {
        val h = Sha1.digest(serial.encodeToByteArray())
        val v = (h[0].toLong() and 0xFF) or ((h[1].toLong() and 0xFF) shl 8) or
            ((h[2].toLong() and 0xFF) shl 16) or ((h[3].toLong() and 0xFF) shl 24)
        return v.toString(16).padStart(8, '0')
    }

    /** A stable 8-hex-digit site id from everything the site carries. */
    fun siteUuid(site: SiteRef): String =
        fnvHex(
            listOf(site.name, site.country.orEmpty(), site.place.orEmpty(), site.latitude?.toString().orEmpty(), site.longitude?.toString().orEmpty())
                .joinToString("\u0000"),
        )

    /** 32-bit FNV-1a of [text]'s chars, as 8 hex digits. */
    fun fnvHex(text: String): String {
        var h = 2166136261u
        for (c in text) { h = h xor c.code.toUInt(); h *= 16777619u }
        return h.toString(16).padStart(8, '0')
    }

    /** Value/1000 with one to three decimals, trailing zeros trimmed ("1.5", "200.0", "12.345"). */
    fun milli(value: Int): String {
        val sign = if (value < 0) "-" else ""
        val a = abs(value)
        val frac = (a % 1000).toString().padStart(3, '0').trimEnd('0').ifEmpty { "0" }
        return "$sign${a / 1000}.$frac"
    }

    /** Degrees with six decimals. */
    fun deg6(value: Double): String {
        val sign = if (value < 0) "-" else ""
        val udeg = (abs(value) * 1_000_000).roundToLong()
        return "$sign${udeg / 1_000_000}.${(udeg % 1_000_000).toString().padStart(6, '0')}"
    }

    /** Percent with one decimal from permille ("32.0%"). */
    fun percent(permille: Int): String = "${permille / 10}.${permille % 10}%"

    /** "M:SS". */
    fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    /** Offset as written: "+7200", "-3600". */
    fun offsetText(seconds: Int): String = if (seconds < 0) "$seconds" else "+$seconds"

    /** Split a comma-separated list ("A, B"), dropping blanks. */
    fun splitList(text: String): List<String> = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * A site from its name, coordinates and taxonomy. Without taxonomy, a "Country / Place /
     * Name" name (as earlier exports of this app wrote it) is split.
     */
    fun site(name: String, lat: Double?, lon: Double?, geo: Map<Int, String>): SiteRef {
        if (geo.isNotEmpty()) {
            val place = geo[GEO_ADMIN_L1] ?: GEO_PLACE_FALLBACK.firstNotNullOfOrNull { geo[it] }
            return SiteRef(name, geo[GEO_COUNTRY], place, lat, lon)
        }
        val parts = name.split(" / ")
        return when (parts.size) {
            3 -> SiteRef(parts[2], parts[0], parts[1], lat, lon)
            2 -> SiteRef(parts[1], parts[0], null, lat, lon)
            else -> SiteRef(name, null, null, lat, lon)
        }
    }

    /** The taxonomy written for a site: category to value. */
    fun geoOf(site: SiteRef): List<Pair<Int, String>> =
        listOfNotNull(site.country?.let { GEO_COUNTRY to it }, site.place?.let { GEO_ADMIN_L1 to it })

    /** Index of the tank holding gas [o2]/[he] (percent), or null. */
    fun cylinderOf(tanks: List<TankEntry>, o2Percent: Int, hePercent: Int): Int? =
        tanks.indexOfFirst { it.o2Permille?.div(10) == o2Percent && (it.hePermille ?: 0) / 10 == hePercent }
            .takeIf { it >= 0 }

    /**
     * A gas switch's value from its o2/he percentages, else from the cylinder it switches
     * to, else from its value (o2 + (he << 16)). A switch naming no mix at all is to air.
     */
    fun gasSwitchValue(o2Text: String?, heText: String?, cylinder: String?, valueText: String?, tanks: List<TankEntry>): Long {
        val o2 = o2Text?.let(FormatUnits::leadingNumber)?.toInt()
        if (o2 != null) return GasSwitch.value(o2, heText?.let(FormatUnits::leadingNumber)?.toInt() ?: 0)
        val tank = cylinder?.trim()?.toIntOrNull()?.let { tanks.getOrNull(it) }
        tank?.o2Permille?.let { return GasSwitch.value(it / 10, (tank.hePermille ?: 0) / 10) }
        val value = valueText?.trim()?.toLongOrNull()?.takeIf { (it and 0xFFFF) > 0 }
        if (value != null) return GasSwitch.value((value and 0xFFFF).toInt(), ((value shr 16) and 0xFFFF).toInt())
        return GasSwitch.value(AIR_O2 / 10, 0)
    }


    /** The values a sample carries forward to the next one (everything but tank pressures). */
    fun carried(previous: Sample, s: Sample) = Sample(
        timeOffsetSeconds = s.timeOffsetSeconds,
        depthMm = s.depthMm ?: previous.depthMm,
        temperatureMk = s.temperatureMk ?: previous.temperatureMk,
        ppO2Mbar = s.ppO2Mbar ?: previous.ppO2Mbar,
        ndlSeconds = s.ndlSeconds ?: previous.ndlSeconds,
        stopDepthMm = s.stopDepthMm ?: previous.stopDepthMm,
        stopTimeSeconds = s.stopTimeSeconds ?: previous.stopTimeSeconds,
        cnsPermille = s.cnsPermille ?: previous.cnsPermille,
    )

    /** Pressures are logged per sensor; [sensorTanks] ties a sensor to its tank. */
    fun mapSensors(samples: List<Sample>, sensorTanks: Map<Int, Int>): List<Sample> =
        if (sensorTanks.isEmpty()) samples else samples.map { s ->
            s.copy(tankPressuresMbar = s.tankPressuresMbar.mapKeys { (sensor, _) -> sensorTanks[sensor] ?: sensor })
        }

    /** The gases of a dive's cylinders, each once. */
    fun gasesOf(tanks: List<TankEntry>): List<GasMix> =
        tanks.mapNotNull { t -> t.o2Permille?.let { GasMix(o2Permille = it, hePermille = t.hePermille ?: 0) } }.distinct()

    /** Oxygen in air, permille. */
    const val AIR_O2 = 210

    /**
     * A dive's temperature as its computers give it: the rounded mean of those that have one.
     * The dive's own temperature is written only where it differs from this.
     */
    fun meanTemp(values: List<Int?>): Int? {
        val known = values.filterNotNull().filter { it != 0 }
        if (known.isEmpty()) return null
        return ((known.sumOf { it.toLong() } + known.size / 2) / known.size).toInt()
    }

    /** The dive's UTC offset, which rides on its first computer's [extra] data; 0 without one. */
    fun utcOffset(extra: Map<String, String>?): Int =
        extra?.get(KEY_UTC_OFFSET)?.trim()?.removePrefix("+")?.toIntOrNull() ?: 0

    /** A computer's own start from its date and time, when it has them, in the dive's frame. */
    fun computerStart(date: String?, time: String?, utcOffset: Int): Long? =
        date?.let { FormatDateTime.epochFromDateTime(it, time ?: "00:00:00") - utcOffset }

    /**
     * A dive as both formats read it: [wallEpochSeconds] is the local wall clock, depths are
     * the first computer's, temperatures the dive's own or else its computers' mean, and
     * computers that only carried the summary are dropped.
     */
    fun dive(
        number: Int?,
        wallEpochSeconds: Long,
        utcOffset: Int,
        durationSeconds: Int,
        rating: Int?,
        visibilityRating: Int?,
        site: SiteRef?,
        notes: String?,
        buddies: List<String>,
        tags: List<String>,
        tanks: List<TankEntry>,
        airTempMk: Int?,
        waterTempMk: Int?,
        computers: List<ComputerEntry>,
    ): DiveEntry {
        val primary = computers.firstOrNull()
        return DiveEntry(
            number = number,
            startEpochSeconds = wallEpochSeconds - utcOffset,
            utcOffsetSeconds = utcOffset,
            durationSeconds = durationSeconds,
            maxDepthMm = primary?.maxDepthMm,
            meanDepthMm = primary?.meanDepthMm,
            waterTempMk = waterTempMk ?: meanTemp(computers.map { it.waterTempMk }),
            airTempMk = airTempMk ?: meanTemp(computers.map { it.airTempMk }),
            notes = notes,
            rating = rating,
            visibilityRating = visibilityRating,
            site = site,
            buddies = buddies,
            tags = tags,
            tanks = tanks,
            gasMixes = gasesOf(tanks),
            computers = computers.filterNot(::isBare),
        )
    }

    /**
     * The computers a dive is written with: a dive without one gets a bare computer carrying
     * its summary, and the first computer takes the dive's summary where it has none.
     */
    fun computersToWrite(dive: DiveEntry): List<ComputerEntry> {
        val computers = dive.computers.ifEmpty {
            val summary = listOf(dive.maxDepthMm, dive.meanDepthMm, dive.waterTempMk, dive.airTempMk)
            if (summary.any { it != null } || dive.utcOffsetSeconds != 0) listOf(ComputerEntry()) else emptyList()
        }
        return computers.mapIndexed { i, c ->
            if (i > 0) c else c.copy(
                maxDepthMm = c.maxDepthMm ?: dive.maxDepthMm,
                meanDepthMm = c.meanDepthMm ?: dive.meanDepthMm,
                waterTempMk = c.waterTempMk ?: dive.waterTempMk,
                airTempMk = c.airTempMk ?: dive.airTempMk,
            )
        }
    }

    /** A computer that names no device and recorded nothing: it only carries the dive's summary. */
    fun isBare(c: ComputerEntry): Boolean =
        c.model == null && c.serial == null && c.samples.isEmpty() && c.events.isEmpty() &&
            c.startEpochSeconds == null && c.durationSeconds == null

    /**
     * The tanks a dive is written with: its tanks, or, when it has none, one gas-only
     * tank per gas mix (the formats keep gases on cylinders only).
     */
    fun tanksToWrite(dive: DiveEntry): List<TankEntry> = dive.tanks.ifEmpty {
        dive.gasMixes.mapIndexed { i, g -> TankEntry(index = i, o2Permille = g.o2Permille, hePermille = g.hePermille) }
    }
}

/** Minimal SHA-1 (FIPS 180-4), for the device ids the formats derive from serial numbers. */
internal object Sha1 {
    fun digest(input: ByteArray): ByteArray {
        val ml = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 1 - i] = (ml ushr (8 * i)).toByte()

        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val w = IntArray(80)
        for (chunk in msg.indices step 64) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                w[i] = ((msg[o].toInt() and 0xFF) shl 24) or ((msg[o + 1].toInt() and 0xFF) shl 16) or
                    ((msg[o + 2].toInt() and 0xFF) shl 8) or (msg[o + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (i in 0 until 80) {
                val (f, k) = when (i) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = t
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        val out = ByteArray(20)
        intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { i, h ->
            for (j in 0 until 4) out[i * 4 + j] = (h ushr (24 - 8 * j)).toByte()
        }
        return out
    }
}
