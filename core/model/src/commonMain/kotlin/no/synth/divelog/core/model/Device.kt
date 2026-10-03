package no.synth.divelog.core.model

/** A physical dive computer the user has downloaded from. */
data class Device(
    val id: Long = UNSAVED_ID,
    val vendor: String,
    val model: String,
    val serial: String? = null,
    val firmware: String? = null,
    val nickname: String? = null,
    val bluetoothAddress: String? = null,
)
