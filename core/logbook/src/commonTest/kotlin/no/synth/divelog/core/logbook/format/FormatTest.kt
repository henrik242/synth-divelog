package no.synth.divelog.core.logbook.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun oneDecimalKeepsTheSignBelowOne() {
        assertEquals("-0.2", Format.oneDecimal(-0.2))
        assertEquals("-1.5", Format.oneDecimal(-1.5))
        assertEquals("0.0", Format.oneDecimal(-0.01))
        assertEquals("12.3", Format.oneDecimal(12.34))
    }
}
