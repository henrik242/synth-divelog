package no.synth.divelog.core.db

import no.synth.divelog.core.db.sql.DiveDatabase
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

    /** Id of an existing device matching [device]'s address, or null if none is stored yet. */
    fun findId(device: Device): Long? =
        device.bluetoothAddress?.let { q.selectDeviceByAddress(it).executeAsOneOrNull()?.id }

    /** Find an existing device by Bluetooth address, or create one. */
    fun getOrCreate(device: Device): Long = db.transactionWithResult {
        val existing = device.bluetoothAddress?.let {
            q.selectDeviceByAddress(it).executeAsOneOrNull()
        }
        existing?.id ?: run {
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
}
