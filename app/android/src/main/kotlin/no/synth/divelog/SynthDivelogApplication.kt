package no.synth.divelog

import android.app.Application
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.logbook.settings.SharedPreferencesStore
import no.synth.divelog.core.logbook.sync.CloudGit
import java.io.File

/** Holds the app's services for the process, so an activity recreation reuses the database. */
class SynthDivelogApplication : Application() {
    val services: AppServices by lazy {
        AppServices(
            database = createDatabase(this),
            settingsStore = SharedPreferencesStore(this),
            cloud = CloudGit(File(filesDir, "cloud").absolutePath),
        )
    }
}
