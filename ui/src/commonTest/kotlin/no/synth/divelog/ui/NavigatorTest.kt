package no.synth.divelog.ui

import androidx.compose.runtime.saveable.SaverScope
import no.synth.divelog.ui.tools.Tool
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigatorTest {
    @Test
    fun restoresSavedStacks() {
        val nav = Navigator(
            Section.SITES,
            mapOf(
                Section.DIVES to listOf(Screen.DiveDetail(3), Screen.DiveEdit(3)),
                Section.SITES to listOf(Screen.Country(1), Screen.Place(2), Screen.Site(4), Screen.SiteEdit(4)),
                Section.TAGS to listOf(Screen.Tag(5)),
                Section.TOOLS to listOf(Screen.ToolPage(Tool.MOD_END)),
                Section.SETTINGS to listOf(Screen.Attributions),
                Section.BUDDIES to listOf(Screen.Buddy(6), Screen.Computers(7)),
            ),
        )
        val saved = with(Navigator.Saver) { SaverScope { true }.save(nav) }
        val restored = Navigator.Saver.restore(requireNotNull(saved))

        requireNotNull(restored)
        assertEquals(Section.SITES, restored.section)
        Section.entries.forEach { s ->
            nav.section = s
            restored.section = s
            assertEquals(nav.stack, restored.stack, "stack of $s")
        }
    }

    @Test
    fun popToKeepsTheFirstScreens() {
        val nav = Navigator()
        nav.push(Screen.DiveDetail(1))
        nav.push(Screen.DiveEdit(1))

        nav.popTo(1)
        assertEquals(listOf<Screen>(Screen.DiveDetail(1)), nav.stack)
        assertEquals(Screen.DiveDetail(1), nav.top)

        nav.open(Section.SITES, listOf(Screen.Site(2)))
        assertEquals(Section.SITES, nav.section)
        assertEquals(listOf<Screen>(Screen.Site(2)), nav.stack)
    }
}
