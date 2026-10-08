package no.synth.divelog.core.model.units

import kotlin.math.round

// Physical unit constants, shared by the parsers, the file formats and the gas tools.

/** 0 degrees Celsius in millikelvin. */
const val ZERO_CELSIUS_MK = 273_150

/** Standard atmosphere, bar. */
const val ATM_BAR = 1.01325

const val MM_PER_FOOT = 304.8
const val FEET_PER_METRE = 1000 / MM_PER_FOOT
const val LITRES_PER_CUFT = 28.316846592
const val BAR_PER_PSI = 0.0689475729
const val PSI_PER_ATM = ATM_BAR / BAR_PER_PSI
const val LBS_PER_KG = 2.20462262

/** Whole millimetres of [feet]. */
fun feetToMm(feet: Int): Int = round(feet * MM_PER_FOOT).toInt()
