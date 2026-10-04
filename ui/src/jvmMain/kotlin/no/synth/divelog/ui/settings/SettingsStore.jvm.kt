package no.synth.divelog.ui.settings

import java.util.prefs.Preferences

actual class SettingsStore {
    private val prefs = Preferences.userRoot().node("no/synth/divelog")

    actual fun getString(key: String): String? = prefs.get(key, null)

    actual fun putString(key: String, value: String) = prefs.put(key, value)
}
