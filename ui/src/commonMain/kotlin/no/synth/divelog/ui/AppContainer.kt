package no.synth.divelog.ui

import no.synth.divelog.core.db.BuddyRepository
import no.synth.divelog.core.db.DeviceRepository
import no.synth.divelog.core.db.DiveRepository
import no.synth.divelog.core.db.GasRepository
import no.synth.divelog.core.db.SiteRepository
import no.synth.divelog.core.db.TagRepository
import no.synth.divelog.core.db.sql.DiveDatabase

/** Holds the repositories the UI needs. Each platform builds the database and
 * passes it in. */
class AppContainer(database: DiveDatabase) {
    val dives = DiveRepository(database)
    val sites = SiteRepository(database)
    val buddies = BuddyRepository(database)
    val tags = TagRepository(database)
    val devices = DeviceRepository(database)
    val gases = GasRepository(database)
}
