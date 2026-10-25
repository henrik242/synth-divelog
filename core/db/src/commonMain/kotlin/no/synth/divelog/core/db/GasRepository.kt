package no.synth.divelog.core.db

import kotlinx.coroutines.flow.Flow
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Tank
import kotlin.math.abs

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
     * Returns the dive's tank index for each incoming tank index that has one, so tank
     * pressure samples can follow their tank.
     *
     * With [follows] the incoming tanks come from a later dive (back-to-back dives joined
     * into one): a matched tank ends at the later dive's end pressure, and one that starts
     * well above where the dive's tank ended is a fresh tank of the same gas, added apart.
     */
    fun mergeTanks(diveId: Long, incoming: List<Tank>, follows: Boolean = false): Map<Int, Int> = db.transactionWithResult {
        if (incoming.isEmpty()) return@transactionWithResult emptyMap()
        val indices = HashMap<Int, Int>()
        val mixes = HashMap<Long, Pair<Int, Int>?>()
        fun gasOf(t: Tank): Pair<Int, Int>? = t.gasMixId?.let { id ->
            mixes.getOrPut(id) { gasMix(id)?.let { it.o2Permille to it.hePermille } }
        }
        val onDive = tanksForDive(diveId)
        val existing = onDive.toMutableList()
        val gasesOnDive = existing.mapNotNull { gasOf(it) }.toMutableSet()
        var nextIndex = (existing.maxOfOrNull { it.index } ?: -1) + 1
        for (t in incoming) {
            val gas = gasOf(t)
            val bare = t.volumeMl == null && t.startPressureMbar == null && t.endPressureMbar == null
            if (bare && (gas == null || gas in gasesOnDive)) {
                gas?.let { g -> onDive.firstOrNull { gasOf(it) == g } }?.let { indices[t.index] = it.index }
                continue
            }
            // After a surface interval a tank carries on from the one of its gas whose end
            // pressure it starts nearest to; one it starts well above was refilled.
            fun carriesOn(e: Tank) = !follows || !isRefill(e, t)
            val sameGas = gas?.let { g -> existing.filter { gasOf(it) == g && carriesOn(it) } }.orEmpty()
            val match = (if (follows) sameGas.minByOrNull { pressureGap(it, t) } else sameGas.firstOrNull())
                ?: existing.firstOrNull { pairsBySize(it, gasOf(it), t, gas) && carriesOn(it) }
            if (match != null) {
                existing.remove(match)
                val filled = match.copy(
                    gasMixId = match.gasMixId ?: t.gasMixId,
                    volumeMl = match.volumeMl ?: t.volumeMl,
                    workingPressureMbar = match.workingPressureMbar ?: t.workingPressureMbar,
                    startPressureMbar = match.startPressureMbar ?: t.startPressureMbar,
                    endPressureMbar = if (follows) t.endPressureMbar ?: match.endPressureMbar else match.endPressureMbar ?: t.endPressureMbar,
                )
                if (filled != match) updateTank(filled)
                indices[t.index] = match.index
            } else {
                indices[t.index] = nextIndex
                addTank(t.copy(diveId = diveId, index = nextIndex++))
                gas?.let { gasesOnDive.add(it) }
            }
        }
        indices
    }

    /** How far [later]'s start pressure is from [earlier]'s end pressure; unknown counts as far. */
    private fun pressureGap(earlier: Tank, later: Tank): Int {
        val ended = earlier.endPressureMbar ?: return Int.MAX_VALUE
        val starts = later.startPressureMbar ?: return Int.MAX_VALUE
        return abs(starts - ended)
    }

    /** Whether [later] starts well above where [earlier] ended: a fresh tank, not the same one. */
    private fun isRefill(earlier: Tank, later: Tank): Boolean {
        val ended = earlier.endPressureMbar ?: return false
        val starts = later.startPressureMbar ?: return false
        return starts - ended > REFILL_MARGIN_MBAR
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

    private companion object {
        /** More than a tank recovers by warming up at the surface. */
        const val REFILL_MARGIN_MBAR = 20_000
    }
}
