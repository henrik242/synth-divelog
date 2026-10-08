package no.synth.divelog.core.model

/** A breathing gas. Fractions are permille; nitrogen is the remainder. */
data class GasMix(
    val id: Long = UNSAVED_ID,
    val o2Permille: Int,
    val hePermille: Int,
) {
    val n2Permille: Int get() = 1_000 - o2Permille - hePermille
}

/** A cylinder on one dive. Pressures millibar, volume millilitres. */
data class Tank(
    val id: Long = UNSAVED_ID,
    val diveId: Long,
    val index: Int,
    val volumeMl: Int? = null,
    val workingPressureMbar: Int? = null,
    val startPressureMbar: Int? = null,
    val endPressureMbar: Int? = null,
    val gasMixId: Long? = null,
)

/**
 * The value of a [EventType.GAS_SWITCH] event: the new mix's O2 and He percent packed as
 * `(o2 shl 8) or he`. Every parser and file format reads and writes it this way.
 */
object GasSwitch {
    fun value(o2Percent: Int, hePercent: Int): Long = ((o2Percent shl 8) or hePercent).toLong()

    fun o2Percent(value: Long): Int = ((value shr 8) and 0xFF).toInt()

    fun hePercent(value: Long): Int = (value and 0xFF).toInt()
}
