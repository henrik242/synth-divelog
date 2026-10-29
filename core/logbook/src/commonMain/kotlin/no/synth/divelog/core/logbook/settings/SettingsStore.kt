package no.synth.divelog.core.logbook.settings

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import no.synth.divelog.core.logbook.download.ConnectionMemory
import no.synth.divelog.core.model.ThemeMode
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
    var unitSystem: UnitSystem by store.enumSetting(KEY_UNITS, UnitSystem.METRIC) { UnitSystem.valueOf(it) }

    /** Whether the app follows the system color scheme or forces light or dark. */
    var themeMode: ThemeMode by store.enumSetting(KEY_THEME, ThemeMode.SYSTEM) { ThemeMode.valueOf(it) }

    var cloudEmail: String by store.stringSetting(KEY_CLOUD_EMAIL, "")
    var cloudPassword: String by store.stringSetting(KEY_CLOUD_PASS, "")

    /** The dive planner's last inputs, as the planner wrote them. */
    var plannerInputs: String? by store.storedString(KEY_PLANNER)

    /** The dive planner's saved plans, as the planner wrote them. */
    var savedPlans: String? by store.storedString(KEY_SAVED_PLANS)

    /** The export target last used, preselected in the export dialog. */
    var exportFormat: String? by store.stringOrNull(KEY_EXPORT_FORMAT)

    /** Whether crash reports are sent, on platforms that have crash reporting. On by default. */
    var crashReporting: Boolean by store.booleanSetting(KEY_CRASH_REPORTING, true)

    /** Where automatic backups go, as the platform named it when the user picked it; null for none. */
    var backupFolder: String? by store.stringOrNull(KEY_BACKUP_FOLDER)

    /** When the last backup was saved to a file or written automatically. */
    var lastBackupEpochSeconds: Long? by store.storedLong(KEY_LAST_BACKUP)

    /** When the last automatic backup was written. */
    var lastAutoBackupEpochSeconds: Long? by store.storedLong(KEY_LAST_AUTO_BACKUP)

    /** Why the last automatic backup failed, or null if it did not. */
    var autoBackupError: String? by store.stringOrNull(KEY_AUTO_BACKUP_ERROR)

    /** When the last automatic backup failed. */
    var autoBackupErrorEpochSeconds: Long? by store.storedLong(KEY_AUTO_BACKUP_ERROR_AT)

    /** Counts changes to the logbook, for telling which ones a backup holds. */
    var backupChanges: Long by store.longSetting(KEY_BACKUP_CHANGES, 0)

    /** [backupChanges] as it was when the last automatic backup that worked began. */
    var backupChangesSaved: Long by store.longSetting(KEY_BACKUP_CHANGES_SAVED, 0)

    /** When the user was last reminded to back up. */
    var backupReminderEpochSeconds: Long? by store.storedLong(KEY_BACKUP_REMINDER)

    override fun recall(deviceKey: String): String? = store.getString(KEY_CONN_PREFIX + deviceKey)

    override fun remember(deviceKey: String, portDescriptor: String) =
        store.putString(KEY_CONN_PREFIX + deviceKey, portDescriptor)

    override fun lastDevice(): String? = store.getString(KEY_LAST_DEVICE)

    override fun rememberLastDevice(deviceKey: String) = store.putString(KEY_LAST_DEVICE, deviceKey)

    private companion object {
        const val KEY_UNITS = "unit_system"
        const val KEY_THEME = "theme_mode"
        const val KEY_LAST_DEVICE = "last_download_device"
        const val KEY_CLOUD_EMAIL = "cloud_email"
        const val KEY_CLOUD_PASS = "cloud_pass"
        const val KEY_CONN_PREFIX = "conn:"
        const val KEY_PLANNER = "planner_inputs"
        const val KEY_SAVED_PLANS = "planner_saved"
        const val KEY_CRASH_REPORTING = "crash_reporting"
        const val KEY_EXPORT_FORMAT = "export_format"
        const val KEY_BACKUP_FOLDER = "backup_folder"
        const val KEY_LAST_BACKUP = "last_backup"
        const val KEY_BACKUP_CHANGES = "backup_changes"
        const val KEY_BACKUP_CHANGES_SAVED = "backup_changes_saved"
        const val KEY_LAST_AUTO_BACKUP = "last_auto_backup"
        const val KEY_AUTO_BACKUP_ERROR = "auto_backup_error"
        const val KEY_AUTO_BACKUP_ERROR_AT = "auto_backup_error_at"
        const val KEY_BACKUP_REMINDER = "backup_reminder"
    }
}

// One store-backed delegate per value shape, so each preference above is a single line.

/** An [Enum] preference stored by name; anything unreadable falls back to [default]. */
private fun <E : Enum<E>> SettingsStore.enumSetting(
    key: String,
    default: E,
    parse: (String) -> E,
) = object : ReadWriteProperty<AppSettings, E> {
    override fun getValue(thisRef: AppSettings, property: KProperty<*>): E =
        runCatching { parse(getString(key) ?: "") }.getOrDefault(default)

    override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: E) = putString(key, value.name)
}

/** A text preference that reads as [default] when nothing is stored. */
private fun SettingsStore.stringSetting(
    key: String,
    default: String,
) = object : ReadWriteProperty<AppSettings, String> {
    override fun getValue(thisRef: AppSettings, property: KProperty<*>): String = getString(key) ?: default

    override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String) = putString(key, value)
}

/** A text preference read exactly as stored, with null written as the empty string. */
private fun SettingsStore.storedString(key: String) = object : ReadWriteProperty<AppSettings, String?> {
    override fun getValue(thisRef: AppSettings, property: KProperty<*>): String? = getString(key)

    override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String?) = putString(key, value ?: "")
}

/** A text preference with no value; the empty string reads as null and null is written as empty. */
private fun SettingsStore.stringOrNull(key: String) = object : ReadWriteProperty<AppSettings, String?> {
    override fun getValue(thisRef: AppSettings, property: KProperty<*>): String? =
        getString(key)?.takeIf { it.isNotEmpty() }

    override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String?) = putString(key, value ?: "")
}

/** A timestamp preference; unreadable text reads as null, null is written as the empty string. */
private fun SettingsStore.storedLong(key: String) = object : ReadWriteProperty<AppSettings, Long?> {
    override fun getValue(thisRef: AppSettings, property: KProperty<*>): Long? = getString(key)?.toLongOrNull()

    override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Long?) =
        putString(key, value?.toString() ?: "")
}

/** A counted preference that reads as [default] when nothing is stored. */
private fun SettingsStore.longSetting(
    key: String,
    default: Long,
) = object : ReadWriteProperty<AppSettings, Long> {
    override fun getValue(thisRef: AppSettings, property: KProperty<*>): Long =
        getString(key)?.toLongOrNull() ?: default

    override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Long) = putString(key, value.toString())
}

/** A true/false preference; anything unreadable reads as [default]. */
private fun SettingsStore.booleanSetting(
    key: String,
    default: Boolean,
) = object : ReadWriteProperty<AppSettings, Boolean> {
    override fun getValue(thisRef: AppSettings, property: KProperty<*>): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Boolean) = putString(key, value.toString())
}
