package no.synth.divelog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun applicationIdIsStable() {
        assertEquals("no.synth.divelog", AppInfo.APPLICATION_ID)
    }

    @Test
    fun nameIsStable() {
        assertEquals("Synth Divelog", AppInfo.NAME)
    }
}
