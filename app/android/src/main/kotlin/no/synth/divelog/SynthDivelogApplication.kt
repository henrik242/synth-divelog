package no.synth.divelog

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.logbook.settings.AppSettings
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

    override fun onCreate() {
        super.onCreate()
        // Read the choice straight from the store, so startup doesn't open the database.
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled =
            AppSettings(SharedPreferencesStore(this)).crashReporting
    }
}
