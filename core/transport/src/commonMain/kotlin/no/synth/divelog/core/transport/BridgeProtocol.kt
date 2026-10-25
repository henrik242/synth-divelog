package no.synth.divelog.core.transport

/**
 * What the macOS Bluetooth helpers and the desktop app say to each other. Both helpers
 * report on stderr "CONNECTED <detail>" once the link is up, or "ERROR <message>" before
 * exiting. ble-bridge's scan prints [DEVICE] and [PROBLEM] lines, and its link runs in
 * [frame]s. One copy for both ends: BleBridge.kt and the JVM's HelperLink and MacBle*.
 */
internal object BridgeProtocol {
    const val CONNECTED = "CONNECTED "
    const val ERROR = "ERROR "

    /** "DEVICE <id>\t<name>", the name empty when it has none. */
    const val DEVICE = "DEVICE "

    /** "PROBLEM <text>", the text empty once Bluetooth can scan again. */
    const val PROBLEM = "PROBLEM "

    /** The first [length] bytes of [data] as one frame: a BE16 length, then the bytes. */
    fun frame(data: ByteArray, length: Int = data.size): ByteArray =
        byteArrayOf((length shr 8).toByte(), length.toByte()) + data.copyOf(length)

    /** The length a frame's two header bytes give. */
    fun frameLength(high: Byte, low: Byte): Int = (high.toInt() and 0xFF shl 8) or (low.toInt() and 0xFF)
}
