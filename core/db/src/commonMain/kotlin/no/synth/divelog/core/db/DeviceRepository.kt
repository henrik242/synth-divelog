package no.synth.divelog.core.db

import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device

/** Dive computers the user has downloaded from. */
class DeviceRepository(private val db: DiveDatabase) {
    private val q = db.deviceQueries

    fun add(device: Device): Long = db.transactionWithResult {
        q.insertDevice(
            device.vendor,
            device.model,
            device.serial,
            device.firmware,
            device.nickname,
            device.bluetoothAddress,
        )
        q.lastInsertRowId().executeAsOne()
    }

    fun all(): List<Device> = q.selectAllDevices().executeAsList().map { it.toDomain() }

    fun get(id: Long): Device? = q.selectDeviceById(id).executeAsOneOrNull()?.toDomain()

    fun byAddress(address: String): Device? =
        q.selectDeviceByAddress(address).executeAsOneOrNull()?.toDomain()

    fun update(device: Device) = q.updateDevice(
        device.vendor,
        device.model,
        device.serial,
        device.firmware,
        device.nickname,
        device.bluetoothAddress,
        device.id,
    )

    fun delete(id: Long) = q.deleteDevice(id)

    /** Number of distinct dives recorded by each device, keyed by device id. */
    fun diveCounts(): Map<Long, Long> = db.recordQueries.countDivesByDevice().executeAsList()
        .associate { it.deviceId to it.dives }

    /** Start time and UTC offset of the newest dive recorded by each device, keyed by device id. */
    fun newestDives(): Map<Long, Pair<Long, Int>> = db.recordQueries.newestDiveByDevice().executeAsList()
        .mapNotNull { row -> row.newest?.let { row.deviceId to (it to row.utcOffsetSeconds.toInt()) } }
        .toMap()

    /** Move every record of [fromId] to [toId], then delete [fromId]. The target's details are kept. */
    fun merge(fromId: Long, toId: Long) {
        if (fromId == toId) return
        db.transaction {
            db.recordQueries.reassignRecordsFromDevice(toId, fromId)
            q.deleteDevice(fromId)
        }
    }

    /** Id of the stored device that is the same computer as [device] (see [findMatch]). */
    fun findId(device: Device): Long? = findMatch(device)?.id

    /**
     * The stored device that is the same computer as [device], or null. Tried in order:
     * the same download identity (the address); the same serial, unless the vendors
     * differ; the only device with the same vendor and model whose serial does not
     * contradict [device]'s. Imports and downloads of one computer so share a device.
     */
    fun findMatch(device: Device): Device? {
        device.bluetoothAddress?.let { address ->
            q.selectDeviceByAddress(address).executeAsOneOrNull()?.let { return it.toDomain() }
        }
        val all = all()
        device.serial?.let { serial ->
            all.firstOrNull { it.serial == serial && sameVendor(it, device) }?.let { return it }
        }
        val key = ComputerNames.key(device.vendor, device.model)
        return all.filter {
            ComputerNames.key(it.vendor, it.model) == key &&
                (it.serial == null || device.serial == null || it.serial == device.serial)
        }.singleOrNull()
    }

    /**
     * The matching device's id ([findMatch]), or a new device. A match learns what it lacks
     * from [device]: its serial, and a download identity in place of none.
     */
    fun getOrCreate(device: Device): Long = db.transactionWithResult {
        val match = findMatch(device)
        if (match != null) {
            val address = match.bluetoothAddress.takeIf { isDownloadIdentity(it) }
                ?: device.bluetoothAddress ?: match.bluetoothAddress
            val learned = match.copy(serial = match.serial ?: device.serial, bluetoothAddress = address)
            if (learned != match) update(learned)
            match.id
        } else {
            q.insertDevice(
                device.vendor,
                device.model,
                device.serial,
                device.firmware,
                device.nickname,
                device.bluetoothAddress,
            )
            q.lastInsertRowId().executeAsOne()
        }
    }

    /**
     * Older imports stored every computer as vendor "Imported" with the logbook's full
     * name as model and an "import:" address. Give them their vendor and model, drop the
     * synthetic address, then merge devices that turn out to be one computer: same vendor
     * and model, and at most one serial between them. Idempotent.
     */
    fun tidyLegacyImports() = db.transaction {
        for (d in all()) {
            if (d.vendor != LEGACY_IMPORT_VENDOR && d.bluetoothAddress?.startsWith(LEGACY_IMPORT_PREFIX) != true) continue
            val (vendor, model) = if (d.vendor == LEGACY_IMPORT_VENDOR) {
                ComputerNames.split(d.model.takeUnless { it in LEGACY_FORMAT_NAMES })
            } else {
                d.vendor to d.model
            }
            update(d.copy(vendor = vendor, model = model, bluetoothAddress = null))
        }
        for (group in all().groupBy { ComputerNames.key(it.vendor, it.model) }.values) {
            if (group.size < 2 || group.mapNotNull { it.serial }.distinct().size > 1) continue
            // Keep a downloaded device (it carries the identity downloads look up), else the oldest.
            val keep = group.firstOrNull { isDownloadIdentity(it.bluetoothAddress) } ?: group.minBy { it.id }
            val serial = keep.serial ?: group.firstNotNullOfOrNull { it.serial }
            group.filter { it.id != keep.id }.forEach { merge(it.id, keep.id) }
            if (serial != keep.serial) update(keep.copy(serial = serial))
        }
    }

    private fun sameVendor(a: Device, b: Device) =
        a.vendor.isBlank() || b.vendor.isBlank() || a.vendor.equals(b.vendor, ignoreCase = true)

    private fun isDownloadIdentity(address: String?) =
        address != null && !address.startsWith(LEGACY_IMPORT_PREFIX)

    private companion object {
        const val LEGACY_IMPORT_VENDOR = "Imported"
        const val LEGACY_IMPORT_PREFIX = "import:"
        /** Format names older imports used when a dive named no computer. */
        val LEGACY_FORMAT_NAMES = setOf("", "Subsurface XML", "UDDF", "MacDive XML", "Subsurface cloud")
    }
}
