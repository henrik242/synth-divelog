package no.synth.divelog.core.logbook.format

import no.synth.divelog.core.model.DiveMode
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

    @Test
    fun diveModes() {
        assertEquals(
            listOf("Open circuit", "Closed circuit", "Semi-closed", "Gauge", "Freedive"),
            DiveMode.entries.map { Format.diveMode(it) },
        )
    }
}
