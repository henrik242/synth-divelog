package no.synth.divelog.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import no.synth.divelog.ui.tools.Tool

internal enum class Section(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    DIVES("Dives", Icons.Outlined.Waves, Icons.Filled.Waves),
    SITES("Sites", Icons.Outlined.Place, Icons.Filled.Place),
    BUDDIES("Buddies", Icons.Outlined.Group, Icons.Filled.Group),
    TAGS("Tags", Icons.Outlined.Sell, Icons.Filled.Sell),
    STATS("Stats", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    TOOLS("Tools", Icons.Outlined.Build, Icons.Filled.Build),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** A page drilled into from a section's root. Holds ids only, so a back stack can be saved. */
internal sealed interface Screen {
    data class DiveDetail(val diveId: Long) : Screen
    data class DiveEdit(val diveId: Long) : Screen
    data class Country(val countryId: Long) : Screen
    data class Place(val placeId: Long) : Screen
    data class Site(val siteId: Long) : Screen
    data class SiteEdit(val siteId: Long) : Screen
    data class Buddy(val buddyId: Long) : Screen
    data class Tag(val tagId: Long) : Screen
    data class ToolPage(val tool: Tool) : Screen

    /** The dive computers page; [focusDeviceId] scrolls to and highlights that device. */
    data class Computers(val focusDeviceId: Long?) : Screen

    data object Attributions : Screen
}

/**
 * The selected [section] and a back stack per section. The breadcrumb shows the current
 * stack and back pops it; switching sections keeps each section's stack.
 */
@Stable
internal class Navigator(section: Section = Section.DIVES, stacks: Map<Section, List<Screen>> = emptyMap()) {
    var section by mutableStateOf(section)
    private val stacks = mutableStateMapOf<Section, List<Screen>>().apply { putAll(stacks) }

    val stack: List<Screen> get() = stacks[section].orEmpty()
    val top: Screen? get() = stack.lastOrNull()

    fun push(screen: Screen) {
        stacks[section] = stack + screen
    }

    fun replaceTop(screen: Screen) {
        stacks[section] = stack.dropLast(1) + screen
    }

    /** Keeps the first [depth] screens (0 is the section root). */
    fun popTo(depth: Int) {
        stacks[section] = stack.take(depth)
    }

    /** Switches to [section] showing [stack]. */
    fun open(section: Section, stack: List<Screen>) {
        this.section = section
        stacks[section] = stack
    }

    companion object {
        /** Saves as strings: the section, then "SECTION|screen,screen" per stack. */
        val Saver: Saver<Navigator, List<String>> = Saver(
            save = { nav ->
                listOf(nav.section.name) + nav.stacks.map { (s, stack) -> s.name + "|" + stack.joinToString(",") { it.encode() } }
            },
            restore = { saved ->
                val stacks = saved.drop(1).mapNotNull { entry ->
                    val section = sectionOf(entry.substringBefore('|')) ?: return@mapNotNull null
                    section to entry.substringAfter('|').split(',').mapNotNull(::decodeScreen)
                }.toMap()
                Navigator(saved.firstOrNull()?.let(::sectionOf) ?: Section.DIVES, stacks)
            },
        )

        private fun sectionOf(name: String) = Section.entries.firstOrNull { it.name == name }
    }
}

private fun Screen.encode(): String = when (this) {
    is Screen.DiveDetail -> "DiveDetail:$diveId"
    is Screen.DiveEdit -> "DiveEdit:$diveId"
    is Screen.Country -> "Country:$countryId"
    is Screen.Place -> "Place:$placeId"
    is Screen.Site -> "Site:$siteId"
    is Screen.SiteEdit -> "SiteEdit:$siteId"
    is Screen.Buddy -> "Buddy:$buddyId"
    is Screen.Tag -> "Tag:$tagId"
    is Screen.ToolPage -> "Tool:${tool.name}"
    is Screen.Computers -> "Computers:${focusDeviceId ?: ""}"
    Screen.Attributions -> "Attributions"
}

private fun decodeScreen(text: String): Screen? {
    val kind = text.substringBefore(':')
    val arg = text.substringAfter(':', "")
    val id = arg.toLongOrNull()
    return when (kind) {
        "DiveDetail" -> id?.let(Screen::DiveDetail)
        "DiveEdit" -> id?.let(Screen::DiveEdit)
        "Country" -> id?.let(Screen::Country)
        "Place" -> id?.let(Screen::Place)
        "Site" -> id?.let(Screen::Site)
        "SiteEdit" -> id?.let(Screen::SiteEdit)
        "Buddy" -> id?.let(Screen::Buddy)
        "Tag" -> id?.let(Screen::Tag)
        "Tool" -> Tool.entries.firstOrNull { it.name == arg }?.let(Screen::ToolPage)
        "Computers" -> Screen.Computers(id)
        "Attributions" -> Screen.Attributions
        else -> null
    }
}
