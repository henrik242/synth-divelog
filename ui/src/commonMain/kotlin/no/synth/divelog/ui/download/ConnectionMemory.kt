package no.synth.divelog.ui.download

/**
 * Remembers which connection (the port [SerialPortInfo.descriptor]) was last used to reach
 * a given device, so the picker can preselect it next time instead of asking every session.
 * Keyed by the device identity (its stored address), so two computers of the same model each
 * keep their own connection. Backed by the platform preference store; a no-op where there is
 * no store (and on platforms with no serial path).
 */
interface ConnectionMemory {
    fun recall(deviceKey: String): String?

    fun remember(deviceKey: String, portDescriptor: String)

    /** The key of the device downloaded from most recently, to preselect it next time. */
    fun lastDevice(): String? = null

    fun rememberLastDevice(deviceKey: String) {}

    /** Remembers nothing; used where no preference store is wired. */
    object None : ConnectionMemory {
        override fun recall(deviceKey: String): String? = null

        override fun remember(deviceKey: String, portDescriptor: String) {}
    }
}
