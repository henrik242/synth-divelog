package no.synth.divelog.core.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val sites = SiteRepository(db)
    private val devices = DeviceRepository(db)

    /** The first two values of [flow], running [write] once the first has arrived. */
    private fun <T> twoValues(flow: Flow<T>, write: () -> Unit): List<T> = runBlocking {
        val values = mutableListOf<T>()
        withTimeout(5_000) {
            flow.take(2).collect {
                values += it
                if (values.size == 1) write()
            }
        }
        values
    }

    @Test
    fun listFlowFollowsAnImport() {
        val dev = devices.add(Device(vendor = "Shearwater", model = "Petrel"))
        val sizes = twoValues(dives.allDivesFlow()) { dives.import(incoming(deviceId = dev)) { false } }.map { it.size }
        assertEquals(listOf(0, 1), sizes)
    }

    @Test
    fun oneOrNullFlowFollowsARename() {
        val siteId = sites.getOrCreateSite("Norway", "Oslo", "Old name")
        val names = twoValues(sites.siteFlow(siteId)) {
            sites.site(siteId)?.let { sites.updateSite(it.copy(name = "New name")) }
        }.map { it?.name }
        assertEquals(listOf("Old name", "New name"), names)
    }
}
