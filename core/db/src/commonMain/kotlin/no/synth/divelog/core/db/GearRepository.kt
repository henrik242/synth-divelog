package no.synth.divelog.core.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearService

/** The diver's equipment and the services of each piece. */
class GearRepository(private val db: DiveDatabase) {
    private val q = db.gearQueries

    fun add(item: GearItem): Long = db.transactionWithResult {
        q.insertGear(
            item.name, item.kind?.name, item.brand, item.model, item.serialNumber,
            item.purchasedEpochDay?.toLong(), item.purchasePriceCents, item.shop, item.notes, if (item.retired) 1 else 0,
        )
        q.lastInsertRowId().executeAsOne()
    }

    fun update(item: GearItem) = q.updateGear(
        item.name, item.kind?.name, item.brand, item.model, item.serialNumber,
        item.purchasedEpochDay?.toLong(), item.purchasePriceCents, item.shop, item.notes, if (item.retired) 1 else 0, item.id,
    )

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

    fun gearIdsByDiveFlow(): Flow<Map<Long, Set<Long>>> = q.selectDiveGearLinks().listFlow { it.diveId to it.gearId }.map(::byDive)

    fun diveIdsForGear(gearId: Long): List<Long> = q.selectDiveIdsForGear(gearId).executeAsList()

    fun diveIdsForGearFlow(gearId: Long): Flow<List<Long>> = q.selectDiveIdsForGear(gearId).listFlow { it }

    /** Every piece of gear with the day of its latest service, or null if never serviced. */
    fun allWithLastService(): List<Pair<GearItem, Int?>> = withLastService(
        all(),
        q.selectLastServices().executeAsList().map { it.gearId to it.epochDay.toInt() },
    )

    /** [allWithLastService] as it changes. */
    fun allWithLastServiceFlow(): Flow<List<Pair<GearItem, Int?>>> =
        combine(
            q.selectAllGear().listFlow { it.toDomain() },
            q.selectLastServices().listFlow { it.gearId to it.epochDay.toInt() },
            ::withLastService,
        )

    private fun withLastService(items: List<GearItem>, last: List<Pair<Long, Int>>): List<Pair<GearItem, Int?>> {
        val byGear = last.toMap()
        return items.map { it to byGear[it.id] }
    }

    /**
     * Adds each of [gear] with its services, skipping any that matches a piece already
     * here by brand, name and serial number. Returns how many were added.
     */
    fun import(gear: List<Pair<GearItem, List<GearService>>>): Int = db.transactionWithResult {
        val known = all().mapTo(mutableSetOf(), ::identity)
        gear.count { (item, services) ->
            known.add(identity(item)) && run {
                val id = add(item)
                services.forEach { addService(it.copy(gearId = id)) }
                true
            }
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
