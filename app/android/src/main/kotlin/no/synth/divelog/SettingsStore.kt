package no.synth.divelog

import android.content.Context
import no.synth.divelog.core.model.units.UnitSystem

/** Small persistent store for app preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("synth-divelog", Context.MODE_PRIVATE)

    var unitSystem: UnitSystem
        get() = runCatching { UnitSystem.valueOf(prefs.getString(KEY_UNITS, null) ?: "") }
            .getOrDefault(UnitSystem.METRIC)
        set(value) { prefs.edit().putString(KEY_UNITS, value.name).apply() }

    var cloudUrl: String
        get() = prefs.getString(KEY_CLOUD_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CLOUD_URL, value).apply() }

    var cloudUsername: String
        get() = prefs.getString(KEY_CLOUD_USER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CLOUD_USER, value).apply() }

    var cloudPassword: String
        get() = prefs.getString(KEY_CLOUD_PASS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CLOUD_PASS, value).apply() }

    private companion object {
        const val KEY_UNITS = "unit_system"
        const val KEY_CLOUD_URL = "cloud_url"
        const val KEY_CLOUD_USER = "cloud_user"
        const val KEY_CLOUD_PASS = "cloud_pass"
    }
}
