package no.synth.divelog.ui.sites

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParseCoordinateTest {
    @Test
    fun acceptsPointOrComma() {
        assertEquals(59.9, parseCoordinate("59.9", 90.0))
        assertEquals(59.9, parseCoordinate(" 59,9 ", 90.0))
        assertEquals(-10.75, parseCoordinate("-10,75", 180.0))
    }

    @Test
    fun rejectsOutOfRangeAndPartialInput() {
        assertNull(parseCoordinate("91", 90.0))
        assertNull(parseCoordinate("-180.5", 180.0))
        assertNull(parseCoordinate("-", 90.0))
        assertNull(parseCoordinate("", 90.0))
    }
}
