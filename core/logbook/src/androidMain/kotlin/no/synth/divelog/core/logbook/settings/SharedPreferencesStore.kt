package no.synth.divelog.core.logbook.settings

import android.content.Context

class SharedPreferencesStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("synth-divelog", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
