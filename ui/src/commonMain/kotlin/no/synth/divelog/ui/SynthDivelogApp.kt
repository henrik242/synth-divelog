package no.synth.divelog.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.model.Dive
import no.synth.divelog.ui.common.BackHandler
import no.synth.divelog.ui.common.observe
import no.synth.divelog.ui.components.Breadcrumb
import no.synth.divelog.ui.components.Crumb
import no.synth.divelog.ui.dive.DivesScreen
import no.synth.divelog.ui.labels.LabelDetail
import no.synth.divelog.ui.labels.LabelKind
import no.synth.divelog.ui.labels.LabelList
import no.synth.divelog.ui.settings.AttributionsScreen
import no.synth.divelog.ui.settings.ComputersScreen
import no.synth.divelog.ui.settings.SettingsSection
import no.synth.divelog.ui.sites.CountryPlaces
import no.synth.divelog.ui.sites.PlaceSites
import no.synth.divelog.ui.sites.SiteDetail
import no.synth.divelog.ui.sites.SiteEditScreen
import no.synth.divelog.ui.sites.SitesOverview
import no.synth.divelog.ui.stats.StatisticsSection
import no.synth.divelog.ui.tools.DivePlannerState
import no.synth.divelog.ui.tools.GasBlenderState
import no.synth.divelog.ui.tools.ModEndState
import no.synth.divelog.ui.tools.TankBuoyancyState
import no.synth.divelog.ui.tools.ToolsSection
import no.synth.divelog.ui.tools.decodePlannerInputs
import no.synth.divelog.ui.tools.decodeSavedPlans
import no.synth.divelog.ui.tools.encodeSavedPlans
import no.synth.divelog.ui.tools.load
import no.synth.divelog.ui.tools.toJson

/**
 * Root of the shared app. The host builds [services] once per process and supplies the
 * platform's [hooks]; everything else (navigation, imports, export, re-parse, cloud push
 * and their status messages) lives here.
 */
@Composable
fun SynthDivelogApp(services: AppServices, hooks: PlatformHooks = PlatformHooks()) {
    SynthTheme { AppContent(services, hooks) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(services: AppServices, hooks: PlatformHooks) {
    val container = services.container
    val settings = services.settings
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    var unitSystem by remember { mutableStateOf(settings.unitSystem) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val addDives = rememberAddDivesFlow(services, hooks, snackbar)
    // The dive detail's previous/next and menu; it sets them while shown.
    var topBarActions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    val buddies = remember(container) { LabelKind.buddies(container) }
    val tags = remember(container) { LabelKind.tags(container) }
    val blender = remember { GasBlenderState() }
    val tank = remember { TankBuoyancyState() }
    val modEnd = remember { ModEndState() }
    val planner = remember {
        DivePlannerState().apply {
            decodePlannerInputs(settings.plannerInputs)?.let { load(it) }
            saved += decodeSavedPlans(settings.savedPlans)
        }
    }
    // Keep the planner's inputs and saved plans, a moment after the last change.
    LaunchedEffect(planner) {
        snapshotFlow { planner.toJson().toString() to encodeSavedPlans(planner.saved) }
            .drop(1)
            .collectLatest { (inputs, saved) ->
                delay(500)
                settings.plannerInputs = inputs
                settings.savedPlans = saved
            }
    }
    val pageState = rememberSaveableStateHolder()

    fun goTo(depth: Int) = nav.popTo(depth)

    fun back() = goTo(nav.stack.size - 1)

    fun openDive(id: Long) = nav.open(Section.DIVES, listOf(Screen.DiveDetail(id)))

    // Straight to a site, with its country and place below it so back steps out level by level.
    fun openSite(id: Long) {
        val site = container.sites.site(id) ?: return
        val place = container.sites.place(site.placeId)
        val parents = place?.let { listOf(Screen.Country(it.countryId), Screen.Place(it.id)) }.orEmpty()
        nav.open(Section.SITES, parents + Screen.Site(id))
    }

    // Runs [work] in the background and shows its message, or the failure, in the snackbar.
    fun runReported(failure: String, work: suspend () -> String?) {
        scope.launch {
            val message = runCatching { work() }.getOrElse { "$failure: ${it.message ?: it::class.simpleName}" }
            message?.let { snackbar.showSnackbar(it) }
        }
    }

    val logbook = services.logbook
    val onExport: (String) -> Unit = { formatId ->
        runReported("Export failed") {
            withContext(Dispatchers.Default) { logbook.export(formatId) }?.let { hooks.saveExport(it) }
            null
        }
    }
    val onReparse: () -> Unit = {
        runReported("Re-parse failed") {
            val count = withContext(Dispatchers.Default) { logbook.reparseAll() }
            "Re-parsed $count dives"
        }
    }
    val onCloudPush = services.cloud?.let { cloud ->
        { email: String, pass: String ->
            runReported("Push failed") {
                cloud.push(email, pass, withContext(Dispatchers.Default) { logbook.exportCloudTree() })
                "Pushed to the cloud"
            }
        }
    }

    val labels = nav.stack.map { screen ->
        observe(screen, read = { screenLabel(screen, container) }, flow = { screenLabelFlow(screen, container) })
    }
    val crumbs = listOf(Crumb(nav.section.label) { goTo(0) }) +
        labels.mapIndexed { i, label -> Crumb(label) { goTo(i + 1) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Breadcrumb(crumbs) },
                actions = { topBarActions?.invoke(this) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { s ->
                    NavigationBarItem(
                        selected = nav.section == s,
                        onClick = { nav.section = s },
                        icon = { Icon(if (nav.section == s) s.selectedIcon else s.icon, contentDescription = s.label) },
                        // Seven tabs on a phone: one line, a size down, or "Settings" wraps.
                        label = { Text(s.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { padding ->
        // Edge-to-edge: the window no longer shrinks for the keyboard, so pad the content by the
        // keyboard height, less the bottom bar it covers, to keep the end of a form reachable.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            // Back pops the section's stack, then returns to Dives. At the Dives root it is
            // left to the platform (Android closes the app).
            BackHandler(enabled = nav.stack.isNotEmpty() || nav.section != Section.DIVES) {
                if (nav.stack.isNotEmpty()) back() else nav.section = Section.DIVES
            }
            val top = nav.top
            // Keyed per page, so a section keeps its list position and filters while another
            // section or the dive computers page is shown.
            pageState.SaveableStateProvider(nav.section.name + if (top is Screen.Computers) "/computers" else "") {
                if (top is Screen.Computers) {
                    ComputersScreen(
                        container = container,
                        onBack = ::back,
                        focusDeviceId = top.focusDeviceId,
                        onFocusConsumed = { nav.replaceTop(Screen.Computers(null)) },
                    )
                } else when (nav.section) {
                    Section.DIVES -> DivesScreen(
                        container = container,
                        unitSystem = unitSystem,
                        openDiveId = (top as? Screen.DiveDetail)?.diveId ?: (top as? Screen.DiveEdit)?.diveId,
                        editing = top is Screen.DiveEdit,
                        onOpenDive = { nav.push(Screen.DiveDetail(it)) },
                        onNavigate = { nav.replaceTop(Screen.DiveDetail(it)) },
                        onEdit = { nav.push(Screen.DiveEdit(it)) },
                        onClose = ::back,
                        onOpenDevice = { nav.push(Screen.Computers(it)) },
                        onOpenSite = ::openSite,
                        onDeleted = ::back,
                        onAddDives = addDives::open,
                        canDownload = hooks.serialPorts.downloadSupported,
                        onTopBarActions = { topBarActions = it },
                    )
                    Section.SITES -> when (top) {
                        is Screen.SiteEdit -> SiteEditScreen(container, top.siteId)
                        is Screen.Site -> SiteDetail(
                            container,
                            top.siteId,
                            unitSystem,
                            onEdit = { nav.push(Screen.SiteEdit(top.siteId)) },
                            onOpenDive = ::openDive,
                        )
                        is Screen.Place -> PlaceSites(container, top.placeId) { nav.push(Screen.Site(it)) }
                        is Screen.Country -> CountryPlaces(container, top.countryId) { nav.push(Screen.Place(it)) }
                        else -> SitesOverview(container, onOpenCountry = { nav.push(Screen.Country(it)) }, onOpenSite = ::openSite)
                    }
                    Section.BUDDIES -> when (top) {
                        is Screen.Buddy -> LabelDetail(
                            buddies, top.buddyId, unitSystem, container,
                            onOpenDive = ::openDive,
                            onDeleted = ::back,
                        )
                        else -> LabelList(buddies) { nav.push(Screen.Buddy(it)) }
                    }
                    Section.TAGS -> when (top) {
                        is Screen.Tag -> LabelDetail(
                            tags, top.tagId, unitSystem, container,
                            onOpenDive = ::openDive,
                            onDeleted = ::back,
                        )
                        else -> LabelList(tags) { nav.push(Screen.Tag(it)) }
                    }
                    Section.STATS -> StatisticsSection(container, unitSystem)
                    Section.TOOLS -> ToolsSection(
                        (top as? Screen.ToolPage)?.tool,
                        { tool -> if (tool == null) back() else nav.push(Screen.ToolPage(tool)) },
                        blender, tank, modEnd, planner, unitSystem,
                        onTopBarActions = { topBarActions = it },
                    )
                    Section.SETTINGS -> if (top == Screen.Attributions) AttributionsScreen() else SettingsSection(
                        settings = settings,
                        unitSystem = unitSystem,
                        onUnitSystemChange = { unitSystem = it; settings.unitSystem = it },
                        onExport = onExport,
                        onReparse = onReparse,
                        onCloudPush = onCloudPush,
                        onOpenComputers = { nav.push(Screen.Computers(null)) },
                        onOpenAttributions = { nav.push(Screen.Attributions) },
                    )
                }
            }

            addDives.Dialogs()
        }
    }
}

/** The breadcrumb label for [screen]. */
private fun screenLabel(screen: Screen, container: AppContainer): String = when (screen) {
    is Screen.DiveDetail -> diveLabel(container.dives.getDive(screen.diveId))
    is Screen.Country -> container.sites.country(screen.countryId)?.name ?: "Country"
    is Screen.Place -> container.sites.place(screen.placeId)?.name ?: "Place"
    is Screen.Site -> container.sites.site(screen.siteId)?.name ?: "Site"
    is Screen.Buddy -> container.buddies.get(screen.buddyId)?.name ?: "Buddy"
    is Screen.Tag -> container.tags.get(screen.tagId)?.name ?: "Tag"
    else -> fixedLabel(screen)
}

/** [screenLabel] as it follows renames. */
private fun screenLabelFlow(screen: Screen, container: AppContainer): Flow<String> = when (screen) {
    is Screen.DiveDetail -> container.dives.diveFlow(screen.diveId).map(::diveLabel)
    is Screen.Country -> container.sites.countryFlow(screen.countryId).map { it?.name ?: "Country" }
    is Screen.Place -> container.sites.placeFlow(screen.placeId).map { it?.name ?: "Place" }
    is Screen.Site -> container.sites.siteFlow(screen.siteId).map { it?.name ?: "Site" }
    is Screen.Buddy -> container.buddies.getFlow(screen.buddyId).map { it?.name ?: "Buddy" }
    is Screen.Tag -> container.tags.getFlow(screen.tagId).map { it?.name ?: "Tag" }
    else -> flowOf(fixedLabel(screen))
}

private fun diveLabel(dive: Dive?): String = dive?.number?.let { "Dive #$it" } ?: "Dive"

private fun fixedLabel(screen: Screen): String = when (screen) {
    is Screen.ToolPage -> screen.tool.label
    is Screen.Computers -> "Dive computers"
    Screen.Attributions -> "Attributions"
    else -> "Edit"
}
