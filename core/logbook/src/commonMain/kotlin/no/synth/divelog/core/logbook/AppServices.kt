package no.synth.divelog.core.logbook

import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.logbook.io.LogbookIo
import no.synth.divelog.core.logbook.settings.AppSettings
import no.synth.divelog.core.logbook.settings.SettingsStore
import no.synth.divelog.core.logbook.sync.CloudSync

/**
 * The long-lived services behind the app, built once per process by the host: the
 * repositories over [database], typed settings over [settingsStore], logbook import and
 * export, and the [cloud] client (null where there is none, which hides the cloud actions).
 */
class AppServices(database: DiveDatabase, settingsStore: SettingsStore, val cloud: CloudSync?) {
    val container = AppContainer(database)
    val settings = AppSettings(settingsStore)
    val logbook = LogbookIo(container)
}
