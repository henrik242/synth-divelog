package no.synth.divelog.core.logbook.settings

import java.util.prefs.Preferences

class JavaPreferencesStore : SettingsStore {
    private val prefs = Preferences.userRoot().node("no/synth/divelog")

    override fun getString(key: String): String? = prefs.get(key, null)

    override fun putString(key: String, value: String) = prefs.put(key, value)
}
