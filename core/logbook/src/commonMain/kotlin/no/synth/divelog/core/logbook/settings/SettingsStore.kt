package no.synth.divelog.core.logbook.settings

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
    var unitSystem: UnitSystem
        get() = runCatching { UnitSystem.valueOf(store.getString(KEY_UNITS) ?: "") }
            .getOrDefault(UnitSystem.METRIC)
        set(value) = store.putString(KEY_UNITS, value.name)

    /** Whether the app follows the system color scheme or forces light or dark. */
    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(store.getString(KEY_THEME) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) = store.putString(KEY_THEME, value.name)

    var cloudEmail: String
        get() = store.getString(KEY_CLOUD_EMAIL) ?: ""
        set(value) = store.putString(KEY_CLOUD_EMAIL, value)

    var cloudPassword: String
        get() = store.getString(KEY_CLOUD_PASS) ?: ""
        set(value) = store.putString(KEY_CLOUD_PASS, value)

    /** The dive planner's last inputs, as the planner wrote them. */
    var plannerInputs: String?
        get() = store.getString(KEY_PLANNER)
        set(value) = store.putString(KEY_PLANNER, value ?: "")

    /** The dive planner's saved plans, as the planner wrote them. */
    var savedPlans: String?
        get() = store.getString(KEY_SAVED_PLANS)
        set(value) = store.putString(KEY_SAVED_PLANS, value ?: "")

    /** The export target last used, preselected in the export dialog. */
    var exportFormat: String?
        get() = store.getString(KEY_EXPORT_FORMAT)?.takeIf { it.isNotEmpty() }
        set(value) = store.putString(KEY_EXPORT_FORMAT, value ?: "")

    /** Whether crash reports are sent, on platforms that have crash reporting. On by default. */
    var crashReporting: Boolean
        get() = store.getString(KEY_CRASH_REPORTING) != "false"
        set(value) = store.putString(KEY_CRASH_REPORTING, value.toString())

    /** Where automatic backups go, as the platform named it when the user picked it; null for none. */
    var backupFolder: String?
        get() = store.getString(KEY_BACKUP_FOLDER)?.takeIf { it.isNotEmpty() }
        set(value) = store.putString(KEY_BACKUP_FOLDER, value ?: "")

    /** When the last backup was saved to a file or written automatically. */
    var lastBackupEpochSeconds: Long?
        get() = store.getString(KEY_LAST_BACKUP)?.toLongOrNull()
        set(value) = store.putString(KEY_LAST_BACKUP, value?.toString() ?: "")

    /** When the last automatic backup was written. */
    var lastAutoBackupEpochSeconds: Long?
        get() = store.getString(KEY_LAST_AUTO_BACKUP)?.toLongOrNull()
        set(value) = store.putString(KEY_LAST_AUTO_BACKUP, value?.toString() ?: "")

    /** Why the last automatic backup failed, or null if it did not. */
    var autoBackupError: String?
        get() = store.getString(KEY_AUTO_BACKUP_ERROR)?.takeIf { it.isNotEmpty() }
        set(value) = store.putString(KEY_AUTO_BACKUP_ERROR, value ?: "")

    /** When the last automatic backup failed. */
    var autoBackupErrorEpochSeconds: Long?
        get() = store.getString(KEY_AUTO_BACKUP_ERROR_AT)?.toLongOrNull()
        set(value) = store.putString(KEY_AUTO_BACKUP_ERROR_AT, value?.toString() ?: "")

    /** Counts changes to the logbook, for telling which ones a backup holds. */
    var backupChanges: Long
        get() = store.getString(KEY_BACKUP_CHANGES)?.toLongOrNull() ?: 0
        set(value) = store.putString(KEY_BACKUP_CHANGES, value.toString())

    /** [backupChanges] as it was when the last automatic backup that worked began. */
    var backupChangesSaved: Long
        get() = store.getString(KEY_BACKUP_CHANGES_SAVED)?.toLongOrNull() ?: 0
        set(value) = store.putString(KEY_BACKUP_CHANGES_SAVED, value.toString())

    /** When the user was last reminded to back up. */
    var backupReminderEpochSeconds: Long?
        get() = store.getString(KEY_BACKUP_REMINDER)?.toLongOrNull()
        set(value) = store.putString(KEY_BACKUP_REMINDER, value?.toString() ?: "")

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
