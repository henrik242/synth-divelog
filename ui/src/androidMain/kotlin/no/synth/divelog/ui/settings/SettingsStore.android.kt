package no.synth.divelog.ui.settings

import android.content.Context

actual class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("synth-divelog", Context.MODE_PRIVATE)

    actual fun getString(key: String): String? = prefs.getString(key, null)

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
