package no.synth.divelog.core.logbook.settings

import platform.Foundation.NSUserDefaults

class UserDefaultsStore : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) = defaults.setObject(value, key)
}
