package no.synth.divelog.ui.settings

import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.download.ConnectionMemory

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

    /** The port descriptor last used to reach the device [deviceKey], or null if none. */
    fun rememberedConnection(deviceKey: String): String? = store.getString(KEY_CONN_PREFIX + deviceKey)

    fun rememberConnection(deviceKey: String, portDescriptor: String) =
        store.putString(KEY_CONN_PREFIX + deviceKey, portDescriptor)

    fun lastDownloadDevice(): String? = store.getString(KEY_LAST_DEVICE)

    fun rememberLastDownloadDevice(deviceKey: String) = store.putString(KEY_LAST_DEVICE, deviceKey)

    private companion object {
        const val KEY_UNITS = "unit_system"
        const val KEY_LAST_DEVICE = "last_download_device"
        const val KEY_CLOUD_EMAIL = "cloud_email"
        const val KEY_CLOUD_PASS = "cloud_pass"
        const val KEY_CONN_PREFIX = "conn:"
    }
}

/** [ConnectionMemory] backed by [AppSettings], so a device's last connection survives restarts. */
class SettingsConnectionMemory(private val settings: AppSettings) : ConnectionMemory {
    override fun recall(deviceKey: String): String? = settings.rememberedConnection(deviceKey)

    override fun remember(deviceKey: String, portDescriptor: String) =
        settings.rememberConnection(deviceKey, portDescriptor)

    override fun lastDevice(): String? = settings.lastDownloadDevice()

    override fun rememberLastDevice(deviceKey: String) = settings.rememberLastDownloadDevice(deviceKey)
}
