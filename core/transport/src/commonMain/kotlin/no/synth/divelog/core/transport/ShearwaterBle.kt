package no.synth.divelog.core.transport

/**
 * What every platform's Bluetooth LE code agrees on for a Shearwater: which scan results are
 * a Peregrine and which GATT service carries its serial link.
 */
internal object ShearwaterBle {
    /**
     * Shearwater's serial service, as Subsurface lists it; not yet confirmed on a device, so
     * [serialServices] also falls back to any vendor service.
     */
    const val SERVICE_UUID = "fe25c237-0ece-443c-b0aa-e02033e7029d"

    /** How long one scan runs; the picker starts another while it waits. */
    const val SCAN_MS = 20_000L

    /** Whether [name] is one a Peregrine advertises ("Peregrine", "Peregrine TX", ...). */
    fun isPeregrineName(name: String?): Boolean = name != null && name.contains("Peregrine", ignoreCase = true)

    /**
     * Whether a scan result is a Peregrine: by its name, or by Shearwater's service when it
     * has none (a blank name counts as none). Other Shearwaters may advertise the same
     * service, so a named one must be a Peregrine; they are left out until their own
     * computer type exists.
     */
    fun isPeregrine(name: String?, advertisesService: Boolean): Boolean =
        isPeregrineName(name) || (advertisesService && name.isNullOrBlank())

    /**
     * The services to search, in order, for the characteristic the serial link rides (one
     * that notifies or indicates and takes writes): Shearwater's, then any other vendor
     * service. Bluetooth SIG services (GAP, GATT, battery, device info) are left out.
     */
    fun <S> serialServices(services: List<S>, uuid: (S) -> String): List<S> {
        val vendor = services.filterNot { isStandard(uuid(it)) }
        return vendor.filter { uuid(it).equals(SERVICE_UUID, ignoreCase = true) } +
            vendor.filterNot { uuid(it).equals(SERVICE_UUID, ignoreCase = true) }
    }

    /**
     * A SIG-assigned UUID: on the Bluetooth base UUID (0000xxxx-0000-1000-8000-00805f9b34fb),
     * or the short 16- or 32-bit form CoreBluetooth prints for one.
     */
    internal fun isStandard(uuid: String): Boolean {
        val u = uuid.lowercase()
        return u.length == 4 || u.length == 8 || u.endsWith(SIG_BASE_SUFFIX)
    }

    private const val SIG_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
}

/** A Bluetooth LE dive computer a scan found: the [id] to connect to it by, and its advertised [name]. */
data class ScannedDevice(val id: String, val name: String?)
