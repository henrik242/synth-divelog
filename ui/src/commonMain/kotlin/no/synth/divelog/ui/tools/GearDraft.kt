package no.synth.divelog.ui.tools

import kotlinx.datetime.LocalDate
import kotlinx.datetime.periodUntil
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.ui.components.parseDate
import no.synth.divelog.ui.components.parseDecimal
import no.synth.divelog.ui.components.twoDecimals
import kotlin.math.roundToLong

/**
 * The fields of a [GearItem] as typed. A blank optional field clears it; a date or price
 * that does not parse keeps the saved value, and so does a blank name.
 */
internal data class GearDraft(
    val name: String,
    val kind: GearKind?,
    val brand: String,
    val model: String,
    val serialNumber: String,
    val purchased: String,
    val price: String,
    val shop: String,
    val notes: String,
    val retired: Boolean = false,
) {
    fun priceValid() = price.isBlank() || parsePrice(price) != null

    fun applyTo(item: GearItem): GearItem = item.copy(
        name = name.trim().ifBlank { item.name },
        kind = kind,
        brand = brand.trim().ifBlank { null },
        model = model.trim().ifBlank { null },
        serialNumber = serialNumber.trim().ifBlank { null },
        purchasedEpochDay = if (purchased.isBlank()) null else parseDate(purchased)?.let(::epochDay) ?: item.purchasedEpochDay,
        purchasePriceCents = if (price.isBlank()) null else parsePrice(price) ?: item.purchasePriceCents,
        shop = shop.trim().ifBlank { null },
        notes = notes.ifBlank { null },
        retired = retired,
    )

    companion object {
        fun of(item: GearItem) = GearDraft(
            name = item.name,
            kind = item.kind,
            brand = item.brand.orEmpty(),
            model = item.model.orEmpty(),
            serialNumber = item.serialNumber.orEmpty(),
            purchased = item.purchasedEpochDay?.let { dateOf(it).toString() }.orEmpty(),
            price = item.purchasePriceCents?.let(::priceText).orEmpty(),
            shop = item.shop.orEmpty(),
            notes = item.notes.orEmpty(),
            retired = item.retired,
        )

        private fun parsePrice(text: String): Long? = parseDecimal(text)?.takeIf { it >= 0 }?.let { (it * 100).roundToLong() }

        private fun priceText(cents: Long) = twoDecimals(cents / 100.0).removeSuffix(".00")
    }
}

internal fun epochDay(date: LocalDate): Int = date.toEpochDays().toInt()

internal fun dateOf(epochDay: Int): LocalDate = LocalDate.fromEpochDays(epochDay)

/** How long before [today] [day] was: "today", "12 days ago", "5 months ago", "3 years 1 month ago". */
internal fun timeSince(day: LocalDate, today: LocalDate): String {
    if (day > today) return "in the future"
    val p = day.periodUntil(today)
    fun count(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
    return when {
        p.years > 0 -> listOfNotNull(count(p.years, "year"), p.months.takeIf { it > 0 }?.let { count(it, "month") }).joinToString(" ") + " ago"
        p.months > 0 -> count(p.months, "month") + " ago"
        p.days > 0 -> count(p.days, "day") + " ago"
        else -> "today"
    }
}

/** [timeSince] for a table column: "3y 1m", "2y", "5m", "12d", "today". */
internal fun shortTimeSince(day: LocalDate, today: LocalDate): String {
    if (day > today) return "future"
    val p = day.periodUntil(today)
    return when {
        p.years > 0 -> if (p.months > 0) "${p.years}y ${p.months}m" else "${p.years}y"
        p.months > 0 -> "${p.months}m"
        p.days > 0 -> "${p.days}d"
        else -> "today"
    }
}

internal val GearKind.label: String
    get() = when (this) {
        GearKind.FIRST_STAGE -> "Regulator 1st stage"
        GearKind.SECOND_STAGE -> "Regulator 2nd stage"
        GearKind.BCD -> "BCD / wing"
        GearKind.BACKPLATE -> "Backplate / harness"
        GearKind.WETSUIT -> "Wetsuit"
        GearKind.DRYSUIT -> "Drysuit"
        GearKind.UNDERSUIT -> "Undersuit"
        GearKind.HOOD -> "Hood"
        GearKind.GLOVES -> "Gloves"
        GearKind.BOOTS -> "Boots"
        GearKind.COMPUTER -> "Dive computer"
        GearKind.SPG -> "Pressure gauge"
        GearKind.COMPASS -> "Compass"
        GearKind.CYLINDER -> "Cylinder"
        GearKind.REBREATHER -> "Rebreather"
        GearKind.DPV -> "Scooter (DPV)"
        GearKind.LIGHT -> "Light"
        GearKind.MASK -> "Mask"
        GearKind.SNORKEL -> "Snorkel"
        GearKind.FINS -> "Fins"
        GearKind.KNIFE -> "Knife / cutter"
        GearKind.SMB -> "SMB / reel"
        GearKind.WEIGHTS -> "Weights"
        GearKind.CAMERA -> "Camera / housing"
        GearKind.OTHER -> "Other"
    }
