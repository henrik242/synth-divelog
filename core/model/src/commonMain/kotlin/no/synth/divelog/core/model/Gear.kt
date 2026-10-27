package no.synth.divelog.core.model

/**
 * A piece of the diver's equipment. Dates are days since 1970-01-01; the price is in
 * hundredths of whatever currency the diver uses.
 */
data class GearItem(
    val id: Long = UNSAVED_ID,
    val name: String,
    val kind: GearKind? = null,
    val brand: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val purchasedEpochDay: Int? = null,
    val purchasePriceCents: Long? = null,
    val shop: String? = null,
    val notes: String? = null,
    /** No longer in use, sold or lost; kept for its history. */
    val retired: Boolean = false,
)

/** A piece of gear with the day of its latest service, null if it has none. */
data class GearWithLastService(val item: GearItem, val lastServiceDay: Int?)

/** A piece of gear read from a file, with the services the file gave it. */
data class ImportedGear(val item: GearItem, val services: List<GearService> = emptyList())

/** One service of a [GearItem]. */
data class GearService(
    val id: Long = UNSAVED_ID,
    val gearId: Long,
    val epochDay: Int,
    val notes: String? = null,
)

enum class GearKind {
    FIRST_STAGE,
    SECOND_STAGE,
    BCD,
    BACKPLATE,
    WETSUIT,
    DRYSUIT,
    UNDERSUIT,
    HOOD,
    GLOVES,
    BOOTS,
    COMPUTER,
    SPG,
    COMPASS,
    CYLINDER,
    REBREATHER,
    DPV,
    LIGHT,
    MASK,
    SNORKEL,
    FINS,
    KNIFE,
    SMB,
    WEIGHTS,
    CAMERA,
    OTHER,
    ;

    companion object {
        /** Parse a stored name, or null for anything unknown. */
        fun fromStored(name: String?): GearKind? = entries.firstOrNull { it.name == name }
    }
}
