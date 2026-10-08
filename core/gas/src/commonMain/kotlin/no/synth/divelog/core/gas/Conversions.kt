package no.synth.divelog.core.gas

import kotlin.math.floor
import kotlin.math.pow

/** Rounds half up to [decimals]: 2.25 -> 2.3, -2.25 -> -2.2. */
internal fun roundHalfUp(value: Double, decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return floor(value * factor + 0.5) / factor
}
