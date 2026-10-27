package no.synth.divelog.core.db

import kotlin.test.Test
import kotlin.test.assertEquals
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearService
import no.synth.divelog.core.model.ImportedGear
import no.synth.divelog.core.model.UNSAVED_ID

class GearImportTest {
    private val gear = GearRepository(testDatabase())

    @Test
    fun importsServicesAndSkipsGearAlreadyThere() {
        gear.add(GearItem(name = "MK25", brand = "Scubapro", serialNumber = "1"))
        val list = listOf(
            ImportedGear(GearItem(name = "mk25", brand = "Scubapro", serialNumber = "1")),
            ImportedGear(GearItem(name = "MK25", brand = "Scubapro", serialNumber = "2"), listOf(GearService(gearId = UNSAVED_ID, epochDay = 18_000))),
        )

        assertEquals(1, gear.import(list))
        assertEquals(0, gear.import(list))
        val added = gear.all().single { it.serialNumber == "2" }
        assertEquals(listOf(18_000), gear.services(added.id).map { it.epochDay })
    }

    @Test
    fun keepsAlikePiecesInOneFile() {
        val stage = GearItem(name = "AL80", brand = "Luxfer")
        assertEquals(2, gear.import(listOf(ImportedGear(stage), ImportedGear(stage))))
        assertEquals(0, gear.import(listOf(ImportedGear(stage))))
    }
}
