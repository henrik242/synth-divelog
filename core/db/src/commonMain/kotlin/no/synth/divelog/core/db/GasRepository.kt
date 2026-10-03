package no.synth.divelog.core.db

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
