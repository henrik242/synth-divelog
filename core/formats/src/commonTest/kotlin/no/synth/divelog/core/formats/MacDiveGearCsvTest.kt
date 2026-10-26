package no.synth.divelog.core.formats

import kotlinx.datetime.LocalDate
import no.synth.divelog.core.model.GearKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacDiveGearCsvTest {
    private val csv = listOf(
        "Manufacturer, Name, Type, Serial, Weight, Purchase Date, Purchase Price, Shop, Warranty, Last Service",
        "\"Apeks\", \"XTX50\", \"Regulator\", \"AB123\", 0.00, \"2014-08-26\", 1700.00, \"Dive shop, Oslo\", \"\", \"2020-01-16\"",
        "\"Acme\", \"12 / 232\", \"Tank\", \"\", 18.20, \"\", 0.00, \"\", \"\", \"\"",
        // A doubled quote is one quote, and a quoted field may span lines.
        "\"NoName\", \"Tank adapter \"\"STA\"\"\", \"STA\", \"\", 0.00, \"\", 400.00, \"A friend\non the forum\", \"2 years\", \"\"",
    ).joinToString("\r\n")

    private fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d).toEpochDays().toInt()

    @Test
    fun readsEveryColumn() {
        val (reg, services) = MacDiveGearCsv.parse(csv)[0]
        assertEquals("XTX50", reg.name)
        assertEquals("Apeks", reg.brand)
        assertEquals(GearKind.FIRST_STAGE, reg.kind)
        assertEquals("AB123", reg.serialNumber)
        assertEquals(day(2014, 8, 26), reg.purchasedEpochDay)
        assertEquals(170_000, reg.purchasePriceCents)
        assertEquals("Dive shop, Oslo", reg.shop)
        assertNull(reg.notes)
        assertEquals(listOf(day(2020, 1, 16)), services.map { it.epochDay })
    }

    @Test
    fun blanksAndZeroesAreLeftOutAndTheRestGoesToNotes() {
        val (tank, tankServices) = MacDiveGearCsv.parse(csv)[1]
        assertEquals(GearKind.CYLINDER, tank.kind)
        assertNull(tank.serialNumber)
        assertNull(tank.purchasedEpochDay)
        assertNull(tank.purchasePriceCents)
        assertEquals("Weight: 18.2 kg", tank.notes)
        assertTrue(tankServices.isEmpty())

        val adapter = MacDiveGearCsv.parse(csv)[2].first
        assertEquals("Tank adapter \"STA\"", adapter.name)
        assertEquals(GearKind.OTHER, adapter.kind)
        assertEquals("A friend\non the forum", adapter.shop)
        assertEquals("Type: STA\nWarranty: 2 years", adapter.notes)
    }

    @Test
    fun anOctopusIsASecondStage() {
        val octopus = "Manufacturer, Name, Type\n\"Scubapro\", \"S600\", \"Octopus\""
        assertEquals(GearKind.SECOND_STAGE, MacDiveGearCsv.parse(octopus).single().first.kind)
    }

    @Test
    fun refusesOtherFiles() {
        assertFailsWith<FormatException> { MacDiveGearCsv.parse("date,depth\n2020-01-01,30") }
    }

    @Test
    fun refusesUnterminatedQuote() {
        assertFailsWith<FormatException> { MacDiveGearCsv.parse("Manufacturer, Name, Type\n\"Apeks, XTX50, Regulator\nMares, Fins, Fins") }
    }
}
