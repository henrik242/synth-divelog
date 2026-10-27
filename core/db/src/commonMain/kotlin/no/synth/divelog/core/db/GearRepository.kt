package no.synth.divelog.core.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearService
import no.synth.divelog.core.model.GearWithLastService
import no.synth.divelog.core.model.ImportedGear

/** The diver's equipment and the services of each piece. */
class GearRepository(private val db: DiveDatabase) {
    private val q = db.gearQueries

    fun add(item: GearItem): Long = db.insertGear(item)

    /** Saves [item]; a dive computer's name goes to its device too. */
    fun update(item: GearItem) = db.transaction {
        db.updateGear(item)
        item.deviceId?.let { db.deviceQueries.setNickname(item.name, it) }
    }

    /** The gear item of the downloaded computer [deviceId]. */
    fun forDevice(deviceId: Long): GearItem? = q.selectGearByDevice(deviceId).executeAsOneOrNull()?.toDomain()

    /**
     * Makes [gearId] the gear item of computer [deviceId]. The item it had until now, made
     * when the computer was first seen, goes into [gearId]: its services and dives move over
     * and it fills in what [gearId] lacks.
     */
    fun linkToDevice(gearId: Long, deviceId: Long) = db.transaction {
        forDevice(deviceId)?.takeIf { it.id != gearId }?.let { db.absorbGear(it.id, gearId) }
        val item = get(gearId) ?: return@transaction
        update(item.copy(deviceId = deviceId))
    }

    /** Deletes [id] and its services. */
    fun delete(id: Long) = q.deleteGear(id)

    fun all(): List<GearItem> = q.selectAllGear().executeAsList().map { it.toDomain() }

    fun allFlow(): Flow<List<GearItem>> = q.selectAllGear().listFlow { it.toDomain() }

    fun get(id: Long): GearItem? = q.selectGearById(id).executeAsOneOrNull()?.toDomain()

    fun getFlow(id: Long): Flow<GearItem?> = q.selectGearById(id).oneOrNullFlow { it.toDomain() }

    fun linkToDive(diveId: Long, gearId: Long) = q.linkDiveGear(diveId, gearId)

    fun unlinkFromDive(diveId: Long, gearId: Long) = q.unlinkDiveGear(diveId, gearId)

    fun gearForDive(diveId: Long): List<GearItem> = q.selectGearForDive(diveId).executeAsList().map { it.toDomain() }

    fun gearForDiveFlow(diveId: Long): Flow<List<GearItem>> = q.selectGearForDive(diveId).listFlow { it.toDomain() }

    /** The gear ids on each dive that has any. */
    fun gearIdsByDive(): Map<Long, Set<Long>> = byDive(q.selectDiveGearLinks().executeAsList().map { it.diveId to it.gearId })

    fun gearIdsByDiveFlow(): Flow<Map<Long, Set<Long>>> = q.selectDiveGearLinks().listFlow { it.diveId to it.gearId }.map(::byDive).flowOn(Dispatchers.Default)

    fun diveIdsForGear(gearId: Long): List<Long> = q.selectDiveIdsForGear(gearId).executeAsList()

    fun diveIdsForGearFlow(gearId: Long): Flow<List<Long>> = q.selectDiveIdsForGear(gearId).listFlow { it }

    /** Every piece of gear with the day of its latest service, or null if never serviced. */
    fun allWithLastService(): List<GearWithLastService> = withLastService(
        all(),
        q.selectLastServices().executeAsList().map { it.gearId to it.epochDay.toInt() },
    )

    /** [allWithLastService] as it changes. */
    fun allWithLastServiceFlow(): Flow<List<GearWithLastService>> =
        combine(
            q.selectAllGear().listFlow { it.toDomain() },
            q.selectLastServices().listFlow { it.gearId to it.epochDay.toInt() },
            ::withLastService,
        )

    private fun withLastService(items: List<GearItem>, last: List<Pair<Long, Int>>): List<GearWithLastService> {
        val byGear = last.toMap()
        return items.map { GearWithLastService(it, byGear[it.id]) }
    }

    /**
     * Adds each of [gear] with its services, skipping any that matches a piece already here
     * by brand, name and serial number; alike pieces in [gear] itself are all added. A dive
     * computer is linked to the one downloaded computer with its serial and maker, if there is
     * just one. Returns how many were added.
     */
    fun import(gear: List<ImportedGear>): Int = db.transactionWithResult {
        val known = all().mapTo(mutableSetOf(), ::identity)
        val devices = db.deviceQueries.selectAllDevices().executeAsList()
        gear.count { (item, services) ->
            if (identity(item) in known) return@count false
            val id = add(item)
            services.forEach { addService(it.copy(gearId = id)) }
            val serial = item.serialNumber?.takeIf { item.kind == GearKind.COMPUTER }
            devices.singleOrNull { serial != null && it.serial == serial && ComputerNames.sameMaker(item.brand, it.vendor) }
                ?.let { linkToDevice(id, it.id) }
            true
        }
    }

    private fun identity(item: GearItem) = listOf(item.brand, item.name, item.serialNumber).map { it.orEmpty().trim().lowercase() }

    fun addService(service: GearService): Long = db.transactionWithResult {
        q.insertService(service.gearId, service.epochDay.toLong(), service.notes)
        q.lastInsertRowId().executeAsOne()
    }

    fun updateService(service: GearService) = q.updateService(service.epochDay.toLong(), service.notes, service.id)

    fun deleteService(id: Long) = q.deleteService(id)

    /** [gearId]'s services, newest first. */
    fun services(gearId: Long): List<GearService> = q.selectServicesForGear(gearId).executeAsList().map { it.toDomain() }

    fun servicesFlow(gearId: Long): Flow<List<GearService>> = q.selectServicesForGear(gearId).listFlow { it.toDomain() }
}

internal fun DiveDatabase.insertGear(item: GearItem): Long = transactionWithResult {
    gearQueries.insertGear(
        item.name, item.kind?.name, item.brand, item.model, item.serialNumber,
        item.purchasedEpochDay?.toLong(), item.purchasePriceCents, item.shop, item.notes, if (item.retired) 1 else 0,
        item.deviceId,
    )
    gearQueries.lastInsertRowId().executeAsOne()
}

internal fun DiveDatabase.updateGear(item: GearItem) = gearQueries.updateGear(
    item.name, item.kind?.name, item.brand, item.model, item.serialNumber,
    item.purchasedEpochDay?.toLong(), item.purchasePriceCents, item.shop, item.notes, if (item.retired) 1 else 0,
    item.deviceId, item.id,
)

/**
 * Folds gear item [fromId] into [toId], for when they are one piece: services and dives move
 * over, [toId]'s empty fields take [fromId]'s, and [fromId] is deleted.
 */
internal fun DiveDatabase.absorbGear(fromId: Long, toId: Long) = transaction {
    val q = gearQueries
    val from = q.selectGearById(fromId).executeAsOneOrNull()?.toDomain() ?: return@transaction
    val to = q.selectGearById(toId).executeAsOneOrNull()?.toDomain() ?: return@transaction
    q.moveServices(toGearId = toId, fromGearId = fromId)
    q.copyGearDives(toGearId = toId, fromGearId = fromId)
    q.deleteGear(fromId)
    val filled = to.copy(
        kind = to.kind ?: from.kind,
        brand = to.brand ?: from.brand,
        model = to.model ?: from.model,
        serialNumber = to.serialNumber ?: from.serialNumber,
        purchasedEpochDay = to.purchasedEpochDay ?: from.purchasedEpochDay,
        purchasePriceCents = to.purchasePriceCents ?: from.purchasePriceCents,
        shop = to.shop ?: from.shop,
        notes = to.notes ?: from.notes,
        deviceId = to.deviceId ?: from.deviceId,
    )
    if (filled != to) updateGear(filled)
}

/** The name a computer's gear item is made with. */
internal fun autoComputerName(device: Device) = ComputerNames.fullName(device).ifBlank { "Dive computer" }

/**
 * The gear item for [device], just stored: the one dive computer in the gear list with its
 * serial and maker, else a new one named by its nickname or [autoComputerName].
 */
internal fun DiveDatabase.gearForNewComputer(device: Device): GearItem = transactionWithResult {
    val existing = device.serial?.let { serial ->
        gearQueries.selectUnlinkedComputersBySerial(serial).executeAsList()
            .filter { ComputerNames.sameMaker(it.brand, device.vendor) }
            .singleOrNull()?.toDomain()
    }
    if (existing != null) {
        existing.copy(deviceId = device.id).also(::updateGear)
    } else {
        val item = GearItem(
            name = device.nickname?.takeIf { it.isNotBlank() } ?: autoComputerName(device),
            kind = GearKind.COMPUTER,
            brand = device.vendor.ifBlank { null },
            model = device.model.ifBlank { null },
            serialNumber = device.serial,
            deviceId = device.id,
        )
        item.copy(id = insertGear(item))
    }
}

/** Computer [fromId] is merging into [toId]: its gear item goes into [toId]'s, or becomes it. */
internal fun DiveDatabase.moveComputerGear(fromId: Long, toId: Long) = transaction {
    val from = gearQueries.selectGearByDevice(fromId).executeAsOneOrNull()?.toDomain() ?: return@transaction
    val to = gearQueries.selectGearByDevice(toId).executeAsOneOrNull()
    if (to != null) absorbGear(from.id, to.id) else updateGear(from.copy(deviceId = toId))
}

/** Computer [before] got a new vendor or model ([after]): a gear item still named as it was made follows. */
internal fun DiveDatabase.renameComputerGear(before: Device, after: Device) = transaction {
    val item = gearQueries.selectGearByDevice(before.id).executeAsOneOrNull()?.toDomain() ?: return@transaction
    if (item.name != autoComputerName(before)) return@transaction
    val name = autoComputerName(after)
    updateGear(item.copy(name = name, brand = after.vendor.ifBlank { null }, model = after.model.ifBlank { null }))
    deviceQueries.setNickname(name, before.id)
}
