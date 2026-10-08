package no.synth.divelog.core.formats

import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Formatting and parsing of the unit-suffixed values used in the file formats. */
internal object FormatUnits {
    fun twoDecimals(value: Double): String {
        val scaled = (value * 100).roundToLong()
        val whole = scaled / 100
        val frac = abs(scaled % 100)
        val sign = if (value < 0 && whole == 0L) "-" else ""
        return "$sign$whole.${frac.toString().padStart(2, '0')}"
    }

    /** Leading numeric part of a value like "35.9 m" or "32.0%". */
    fun leadingNumber(text: String): Double? {
        val sb = StringBuilder()
        for (c in text.trim()) {
            if (c.isDigit() || c == '.' || c == '-' || c == '+') sb.append(c) else break
        }
        return sb.toString().toDoubleOrNull()
    }

    /** Metres, bar or litres, any suffix, to mm, mbar or ml: "35.9 m" is 35900. */
    fun thousandths(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() }

    /** Celsius, any suffix, to millikelvin. */
    fun celsiusToMk(text: String): Int? = thousandths(text)?.let { it + ZERO_CELSIUS_MK }

    /** Percent, any suffix, to permille. */
    fun percentToPermille(text: String): Int? = leadingNumber(text)?.let { (it * 10).roundToInt() }

    /** "M:SS", "M:SS min" or plain seconds. */
    fun clockToSeconds(text: String): Int? {
        val cleaned = text.trim().removeSuffix("min").trim()
        val parts = cleaned.split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].trim().toIntOrNull() ?: return null
                val s = parts[1].trim().toIntOrNull() ?: return null
                m * 60 + s
            }
            1 -> parts[0].trim().toIntOrNull()
            else -> null
        }
    }

    /** A plain SI number, which may use an exponent ("1.2e5"). */
    private fun si(text: String): Double? = text.trim().toDoubleOrNull() ?: leadingNumber(text)

    // SI plain decimals (UDDF): metres, Kelvin, gas fraction
    fun siMetres(mm: Int): String = (mm / 1000.0).toString()
    fun siMetresToMm(text: String): Int? = si(text)?.let { (it * 1000).roundToInt() }
    fun siKelvin(mk: Int): String = (mk / 1000.0).toString()
    fun siKelvinToMk(text: String): Int? = si(text)?.let { (it * 1000).roundToInt() }
    fun siFraction(permille: Int): String = (permille / 1000.0).toString()
    fun siCubicMetres(ml: Int): String = (ml / 1_000_000.0).toString()
    fun siCubicMetresToMl(text: String): Int? = si(text)?.let { (it * 1_000_000).roundToInt() }
    fun siPascal(mbar: Int): String = (mbar * 100L).toString()
    fun siPascalToMbar(text: String): Int? = si(text)?.let { (it / 100).roundToInt() }
    fun siFractionToPermille(text: String): Int? = si(text)?.let { (it * 1000).roundToInt() }

    // MacDive plain decimals: metres, Celsius, bar, integer percent, litres, minutes
    fun macDepth(mm: Int): String = twoDecimals(mm / 1000.0)
    fun macCelsius(mk: Int): String = twoDecimals((mk - ZERO_CELSIUS_MK) / 1000.0)
    fun macBar(mbar: Int): String = twoDecimals(mbar / 1000.0)
    fun macPercent(permille: Int): String = (permille / 10).toString()
    fun macLitres(ml: Int): String = twoDecimals(ml / 1000.0)
    fun macMinutes(seconds: Int): String = (seconds / 60).toString()
    fun macMinutesToSeconds(text: String): Int? = leadingNumber(text)?.let { (it * 60).roundToInt() }
    fun macSeconds(text: String): Int? = leadingNumber(text)?.roundToInt()
}
