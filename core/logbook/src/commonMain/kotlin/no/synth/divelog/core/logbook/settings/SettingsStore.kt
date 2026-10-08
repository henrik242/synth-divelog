package no.synth.divelog.core.logbook.settings

import no.synth.divelog.core.logbook.download.ConnectionMemory
import no.synth.divelog.core.model.units.UnitSystem

/** Platform key-value store for app preferences, backed by each platform's native store. */
interface SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/**
 * Typed app preferences over a platform [SettingsStore]. Keys and defaults are shared.
 * Also the download's [ConnectionMemory], so a device's last connection survives restarts.
 */
class AppSettings(private val store: SettingsStore) : ConnectionMemory {
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

    override fun recall(deviceKey: String): String? = store.getString(KEY_CONN_PREFIX + deviceKey)

    override fun remember(deviceKey: String, portDescriptor: String) =
        store.putString(KEY_CONN_PREFIX + deviceKey, portDescriptor)

    override fun lastDevice(): String? = store.getString(KEY_LAST_DEVICE)

    override fun rememberLastDevice(deviceKey: String) = store.putString(KEY_LAST_DEVICE, deviceKey)

    private companion object {
        const val KEY_UNITS = "unit_system"
        const val KEY_LAST_DEVICE = "last_download_device"
        const val KEY_CLOUD_EMAIL = "cloud_email"
        const val KEY_CLOUD_PASS = "cloud_pass"
        const val KEY_CONN_PREFIX = "conn:"
    }
}
