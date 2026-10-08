package no.synth.divelog.core.db

import kotlinx.coroutines.flow.Flow
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Tank

/** Breathing gases and the cylinders on a dive. */
class GasRepository(private val db: DiveDatabase) {
    private val q = db.gasQueries

    fun getOrCreateGasMix(o2Permille: Int, hePermille: Int): Long = db.transactionWithResult {
        q.findGasMix(o2Permille.toLong(), hePermille.toLong()).executeAsOneOrNull()?.id ?: run {
            q.insertGasMix(o2Permille.toLong(), hePermille.toLong())
            q.lastInsertRowId().executeAsOne()
        }
    }

    fun gasMix(id: Long): GasMix? = q.selectGasMixById(id).executeAsOneOrNull()?.toDomain()

    /** Every gas mix by id. */
    fun allGasMixes(): Map<Long, GasMix> = q.selectAllGasMixes().executeAsList().associate { it.id to it.toDomain() }

    fun addTank(tank: Tank): Long = db.transactionWithResult {
        q.insertTank(
            tank.diveId,
            tank.index.toLong(),
            tank.volumeMl?.toLong(),
            tank.workingPressureMbar?.toLong(),
            tank.startPressureMbar?.toLong(),
            tank.endPressureMbar?.toLong(),
            tank.gasMixId,
        )
        q.lastInsertRowId().executeAsOne()
    }

    fun tanksForDive(diveId: Long): List<Tank> =
        q.selectTanksForDive(diveId).executeAsList().map { it.toDomain() }

    fun tanksForDiveFlow(diveId: Long): Flow<List<Tank>> = q.selectTanksForDive(diveId).listFlow { it.toDomain() }

    /** Every dive's tanks in index order, by dive id, in one query. */
    fun tanksByDive(): Map<Long, List<Tank>> =
        q.selectAllTanks().executeAsList().groupBy({ it.diveId }) { it.toDomain() }

    /**
     * Merge [incoming] tanks onto [diveId]: each fills in what an unclaimed matching tank
     * lacks (size, pressures, gas); the rest are added after the dive's tanks. A tank that
     * only names a gas the dive already has adds nothing. The incoming ids are ignored.
     */
    fun mergeTanks(diveId: Long, incoming: List<Tank>) = db.transaction {
        if (incoming.isEmpty()) return@transaction
        val mixes = HashMap<Long, Pair<Int, Int>?>()
        fun gasOf(t: Tank): Pair<Int, Int>? = t.gasMixId?.let { id ->
            mixes.getOrPut(id) { gasMix(id)?.let { it.o2Permille to it.hePermille } }
        }
        val existing = tanksForDive(diveId).toMutableList()
        val gasesOnDive = existing.mapNotNull { gasOf(it) }.toMutableSet()
        var nextIndex = (existing.maxOfOrNull { it.index } ?: -1) + 1
        for (t in incoming) {
            val gas = gasOf(t)
            val bare = t.volumeMl == null && t.startPressureMbar == null && t.endPressureMbar == null
            if (bare && (gas == null || gas in gasesOnDive)) continue
            val match = gas?.let { g -> existing.firstOrNull { gasOf(it) == g } }
                ?: existing.firstOrNull { pairsBySize(it, gasOf(it), t, gas) }
            if (match != null) {
                existing.remove(match)
                val filled = match.copy(
                    gasMixId = match.gasMixId ?: t.gasMixId,
                    volumeMl = match.volumeMl ?: t.volumeMl,
                    workingPressureMbar = match.workingPressureMbar ?: t.workingPressureMbar,
                    startPressureMbar = match.startPressureMbar ?: t.startPressureMbar,
                    endPressureMbar = match.endPressureMbar ?: t.endPressureMbar,
                )
                if (filled != match) updateTank(filled)
            } else {
                addTank(t.copy(diveId = diveId, index = nextIndex++))
                gas?.let { gasesOnDive.add(it) }
            }
        }
    }

    /**
     * Whether an incoming tank of unmatched gas is [existing]: a tank without a gas pairs with
     * one of the same size, and a known gas fills in a gas-less tank of a compatible size.
     * No gas is ever assumed.
     */
    private fun pairsBySize(existing: Tank, existingGas: Pair<Int, Int>?, incoming: Tank, incomingGas: Pair<Int, Int>?): Boolean {
        if (incomingGas == null) return incoming.volumeMl != null && existing.volumeMl == incoming.volumeMl
        val sizeFits = existing.volumeMl == null || incoming.volumeMl == null || existing.volumeMl == incoming.volumeMl
        return existingGas == null && sizeFits
    }

    fun updateTank(tank: Tank) = q.updateTank(
        tank.index.toLong(),
        tank.volumeMl?.toLong(),
        tank.workingPressureMbar?.toLong(),
        tank.startPressureMbar?.toLong(),
        tank.endPressureMbar?.toLong(),
        tank.gasMixId,
        tank.id,
    )

    fun deleteTank(id: Long) = q.deleteTank(id)
}
