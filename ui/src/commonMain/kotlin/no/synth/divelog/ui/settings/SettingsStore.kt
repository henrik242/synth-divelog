package no.synth.divelog.ui.settings

import no.synth.divelog.core.model.units.UnitSystem

/**
 * Platform key-value store for app preferences. Each platform supplies its own
 * constructor and backs this with its native store.
 */
expect class SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Typed app preferences over a platform [SettingsStore]. Keys and defaults are shared. */
class AppSettings(private val store: SettingsStore) {
    var unitSystem: UnitSystem
        get() = runCatching { UnitSystem.valueOf(store.getString(KEY_UNITS) ?: "") }
            .getOrDefault(UnitSystem.METRIC)
        set(value) = store.putString(KEY_UNITS, value.name)

    var cloudEmail: String
        get() = store.getString(KEY_CLOUD_EMAIL) ?: ""
        set(value) = store.putString(KEY_CLOUD_EMAIL, value)

    var cloudPassword: String
        get() = store.getString(KEY_CLOUD_PASS) ?: ""
        set(value) = store.putString(KEY_CLOUD_PASS, value)

    private companion object {
        const val KEY_UNITS = "unit_system"
        const val KEY_CLOUD_EMAIL = "cloud_email"
        const val KEY_CLOUD_PASS = "cloud_pass"
    }
}
