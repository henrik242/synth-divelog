package no.synth.divelog.core.model

/**
 * Dive-computer names as logbooks write them ("Shearwater Research, Inc Petrel",
 * "Suunto HelO2") versus how a download names the same unit (vendor "Shearwater",
 * model "Petrel"). [split] maps the first to the second so both meet on one device.
 */
object ComputerNames {
    /** Name prefixes, longest first, and the vendor each stands for. */
    private val VENDORS = listOf(
        "Shearwater Research, Inc." to "Shearwater",
        "Shearwater Research, Inc" to "Shearwater",
        "Shearwater Research" to "Shearwater",
        "Heinrichs Weikamp" to "Heinrichs Weikamp",
        "Atomic Aquatics" to "Atomic Aquatics",
        "Aqua Lung" to "Aqualung",
        "Dive Rite" to "Dive Rite",
        "Deep Six" to "Deep Six",
    ) + listOf(
        "Shearwater", "Suunto", "Mares", "Scubapro", "Uwatec", "Oceanic", "Aqualung", "Aeris",
        "Sherwood", "Cressi", "Garmin", "Ratio", "Seac", "Tusa", "Hollis", "Apeks", "Citizen",
        "Cochran", "Divesoft", "Liquivision", "McLean", "Deepblu", "Crest", "Genesis", "Beuchat",
        "Zeagle", "Halcyon", "Oceans",
    ).map { it to it }

    /** Name for a computer the log does not name. */
    const val UNKNOWN = "Unknown computer"

    /**
     * Vendor and model of a logbook's computer name. An unrecognised vendor leaves the
     * whole name as the model with an empty vendor.
     */
    fun split(name: String?): Pair<String, String> {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return "" to UNKNOWN
        for ((prefix, vendor) in VENDORS) {
            if (trimmed.length > prefix.length && trimmed.startsWith("$prefix ", ignoreCase = true)) {
                return vendor to trimmed.substring(prefix.length).trim()
            }
        }
        return "" to trimmed
    }

    /** "Shearwater Petrel": the name logbooks expect on export. */
    fun fullName(device: Device): String = listOf(device.vendor, device.model).filter { it.isNotBlank() }.joinToString(" ")

    /** Case- and spacing-insensitive identity of a vendor and model, for matching. */
    fun key(vendor: String, model: String): String =
        "$vendor $model".lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
