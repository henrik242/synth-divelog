package no.synth.divelog.core.formats

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Formatting and parsing of the unit-suffixed values used in the file formats. */
internal object FormatUnits {
    fun oneDecimal(value: Double): String {
        val scaled = (value * 10).roundToLong()
        val whole = scaled / 10
        val frac = abs(scaled % 10)
        return "$whole.$frac"
    }

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

    // depth: metres with one decimal
    fun depthToMetres(mm: Int): String = "${oneDecimal(mm / 1000.0)} m"
    fun metresToMm(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() }

    // temperature: Celsius with one decimal; storage is millikelvin
    fun tempToCelsius(mk: Int): String = "${oneDecimal((mk - 273_150) / 1000.0)} C"
    fun celsiusToMk(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() + 273_150 }

    // time / duration: "M:SS min"
    fun secondsToClock(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "$m:${s.toString().padStart(2, '0')} min"
    }

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

    // gas fraction: percent with one decimal; storage is permille
    fun permilleToPercent(permille: Int): String = "${oneDecimal(permille / 10.0)}%"
    fun percentToPermille(text: String): Int? = leadingNumber(text)?.let { (it * 10).roundToInt() }

    // pressure: bar; storage is millibar
    fun mbarToBar(mbar: Int): String = "${oneDecimal(mbar / 1000.0)} bar"
    fun barToMbar(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() }

    // volume: litres; storage is millilitres
    fun mlToLitres(ml: Int): String = "${oneDecimal(ml / 1000.0)} l"
    fun litresToMl(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() }

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
    fun macDepthToMm(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() }
    fun macCelsius(mk: Int): String = twoDecimals((mk - 273_150) / 1000.0)
    fun macCelsiusToMk(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() + 273_150 }
    fun macBar(mbar: Int): String = twoDecimals(mbar / 1000.0)
    fun macBarToMbar(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() }
    fun macPercent(permille: Int): String = (permille / 10).toString()
    fun macPercentToPermille(text: String): Int? = leadingNumber(text)?.let { (it * 10).roundToInt() }
    fun macLitres(ml: Int): String = twoDecimals(ml / 1000.0)
    fun macLitresToMl(text: String): Int? = leadingNumber(text)?.let { (it * 1000).roundToInt() }
    fun macMinutes(seconds: Int): String = (seconds / 60).toString()
    fun macMinutesToSeconds(text: String): Int? = leadingNumber(text)?.let { (it * 60).roundToInt() }
    fun macSeconds(text: String): Int? = leadingNumber(text)?.roundToInt()
}
