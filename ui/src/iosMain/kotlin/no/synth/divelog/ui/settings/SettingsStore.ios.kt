package no.synth.divelog.ui.settings

import platform.Foundation.NSUserDefaults

actual class SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun getString(key: String): String? = defaults.stringForKey(key)

    actual fun putString(key: String, value: String) = defaults.setObject(value, key)
}
