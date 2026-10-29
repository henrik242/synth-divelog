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
import androidx.compose.runtime.key
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
import no.synth.divelog.core.model.ThemeMode
import no.synth.divelog.ui.common.BackHandler
import no.synth.divelog.ui.common.observe
import no.synth.divelog.core.logbook.io.ExportFile
import no.synth.divelog.ui.components.Breadcrumb
import no.synth.divelog.ui.components.Crumb
import no.synth.divelog.ui.components.ExportAction
import no.synth.divelog.ui.components.ExportActions
import no.synth.divelog.ui.dive.DiveFilter
import no.synth.divelog.ui.dive.FacetKind
import no.synth.divelog.ui.dive.DivesScreen
import no.synth.divelog.ui.labels.LabelDetail
import no.synth.divelog.ui.labels.LabelKind
import no.synth.divelog.ui.labels.LabelList
import no.synth.divelog.ui.settings.AttributionsScreen
import no.synth.divelog.ui.settings.SettingsSection
import no.synth.divelog.ui.sites.CountryPlaces
import no.synth.divelog.ui.sites.PlaceSites
import no.synth.divelog.ui.sites.SiteDetail
import no.synth.divelog.ui.sites.SiteEditScreen
import no.synth.divelog.ui.sites.SitesOverview
import no.synth.divelog.ui.stats.StatisticsSection
import no.synth.divelog.ui.tools.DivePlannerState
import no.synth.divelog.ui.tools.GasBlenderState
import no.synth.divelog.ui.tools.GearItemScreen
import no.synth.divelog.ui.tools.ModEndState
import no.synth.divelog.ui.tools.Tool
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
    val settings = services.settings
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    SynthTheme(themeMode.darkTheme()) {
        AppContent(services, hooks, themeMode, onThemeModeChange = {
            themeMode = it
            settings.themeMode = it
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(
    services: AppServices,
    hooks: PlatformHooks,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val container = services.container
    val settings = services.settings
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    var unitSystem by remember { mutableStateOf(settings.unitSystem) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val addDives = rememberAddDivesFlow(services, hooks, snackbar) { id ->
        nav.open(Section.DIVES, listOf(Screen.DiveDetail(id), Screen.DiveEdit(id)))
    }
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
    // The dive list's search and filter; a piece of gear's page clears the search and filters to that gear.
    var diveQuery by rememberSaveable { mutableStateOf("") }
    var diveFilter by rememberSaveable(stateSaver = DiveFilter.Saver) { mutableStateOf(DiveFilter()) }

    fun goTo(depth: Int) = nav.popTo(depth)

    fun back() = goTo(nav.stack.size - 1)

    fun openDive(id: Long) = nav.open(Section.DIVES, listOf(Screen.DiveDetail(id)))

    // After the logbook is replaced or emptied: screens, filters and each page's saved state (list
    // position, selection, open dialog) may name rows that are gone, so start over at the dive list.
    fun startOver() {
        diveQuery = ""
        diveFilter = DiveFilter()
        Section.entries.forEach { section -> PAGES.forEach { pageState.removeState(section.name + it) } }
        nav.reset(Section.DIVES)
    }
    // Saved, so a restore that finished while the activity was being recreated still starts over.
    var replacementsSeen by rememberSaveable { mutableStateOf(services.logbookReplacements.value) }
    LaunchedEffect(services) {
        services.logbookReplacements.collect {
            if (it != replacementsSeen) {
                replacementsSeen = it
                startOver()
            }
        }
    }

    // Straight to a site, with its country and place below it so back steps out level by level.
    fun openSite(id: Long) {
        val site = container.sites.site(id) ?: return
        val place = container.sites.place(site.placeId)
        val parents = place?.let { listOf(Screen.Country(it.countryId), Screen.Place(it.id)) }.orEmpty()
        nav.open(Section.SITES, parents + Screen.Site(id))
    }

    // Straight to a piece of gear, with the gear list below it.
    fun openGear(id: Long) = nav.open(Section.TOOLS, listOf(Screen.ToolPage(Tool.GEAR), Screen.Gear(id)))

    fun runReported(failure: String, work: suspend () -> String?) = scope.launchReported(snackbar, failure, work)

    val logbook = services.logbook
    // An export built off the main thread and handed to [deliver]; once that reports the file went
    // out, the caller's onDone runs and [doneMessage] (if any) is shown.
    fun exportAction(doneMessage: (ExportFile) -> String?, deliver: suspend (ExportFile) -> Boolean): ExportAction =
        { formatId, diveIds, onDone ->
            settings.exportFormat = formatId
            runReported("Export failed") {
                val file = withContext(Dispatchers.Default) { logbook.export(formatId, diveIds) }
                if (file != null && deliver(file)) {
                    onDone()
                    doneMessage(file)
                } else {
                    null
                }
            }
        }
    val exportActions = remember(hooks) {
        ExportActions(
            save = hooks.saveExport?.let { save -> exportAction({ "Saved ${it.name}" }, save) },
            share = hooks.shareExport?.let { share -> exportAction({ null }) { share(it); true } },
            lastFormatId = { settings.exportFormat },
        )
    }
    val backup = rememberBackupFlow(
        services, hooks, snackbar,
        onOpenSettings = { nav.open(Section.SETTINGS, emptyList()) },
    )
    backup.Effects()
    val onReparse: () -> Unit = {
        runReported("Re-parse failed") {
            val count = withContext(Dispatchers.Default) { logbook.reparseAll() }
            "Re-parsed $count dives"
        }
    }
    val onForgetAllDives: () -> Unit = {
        runReported("Forgetting dives failed") {
            services.replaceLogbook { container.dives.forgetAllDives() }
            "Forgot all dives"
        }
    }
    val onImportGear = hooks.pickImportFile?.let { pick ->
        {
            runReported("Gear import failed") {
                pick()?.let { bytes -> withContext(Dispatchers.Default) { logbook.importGearMessage(bytes) } }
            }
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
            // section is shown. A page shown over a list gets its own key, so the list keeps its
            // state while it is away.
            val page = if (top is Screen.Gear) GEAR_PAGE else ""
            // Rebuilt when the logbook is replaced: startOver's removeState does not reach the page on screen.
            key(replacementsSeen) {
                pageState.SaveableStateProvider(nav.section.name + page) {
                    when (nav.section) {
                        Section.DIVES -> DivesScreen(
                            container = container,
                            unitSystem = unitSystem,
                            openDiveId = (top as? Screen.DiveDetail)?.diveId ?: (top as? Screen.DiveEdit)?.diveId,
                            editing = top is Screen.DiveEdit,
                            onOpenDive = { nav.push(Screen.DiveDetail(it)) },
                            onNavigate = { nav.replaceTop(Screen.DiveDetail(it)) },
                            onEdit = { nav.push(Screen.DiveEdit(it)) },
                            onClose = ::back,
                            onOpenDevice = { id -> container.gear.forDevice(id)?.let { openGear(it.id) } },
                            onOpenSite = ::openSite,
                            onOpenGear = ::openGear,
                            query = diveQuery,
                            onQuery = { diveQuery = it },
                            filter = diveFilter,
                            onFilter = { diveFilter = it },
                            onDeleted = ::back,
                            onAddDives = addDives::open,
                            canDownload = hooks.serialPorts.downloadSupported,
                            exportActions = exportActions,
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
                        Section.TOOLS -> if (top is Screen.Gear) {
                            GearItemScreen(
                                container, top.gearId,
                                onDeleted = ::back,
                                onShowDives = {
                                    diveQuery = ""
                                    diveFilter = DiveFilter().with(FacetKind.GEAR, setOf(top.gearId))
                                    nav.open(Section.DIVES, emptyList())
                                },
                            )
                        } else {
                            ToolsSection(
                                (top as? Screen.ToolPage)?.tool,
                                { tool -> if (tool == null) back() else nav.push(Screen.ToolPage(tool)) },
                                blender, tank, modEnd, planner, unitSystem, container,
                                onOpenGear = { nav.push(Screen.Gear(it)) },
                                onImportGear = onImportGear,
                                onTopBarActions = { topBarActions = it },
                            )
                        }
                        Section.SETTINGS -> if (top == Screen.Attributions) AttributionsScreen() else SettingsSection(
                            settings = settings,
                            unitSystem = unitSystem,
                            onUnitSystemChange = { unitSystem = it; settings.unitSystem = it },
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            exportActions = exportActions,
                            backup = backup,
                            onReparse = onReparse,
                            onForgetAllDives = onForgetAllDives,
                            onCloudPush = onCloudPush,
                            onOpenAttributions = { nav.push(Screen.Attributions) },
                            onCrashReportingChange = hooks.setCrashReporting?.let { apply ->
                                { enabled: Boolean ->
                                    settings.crashReporting = enabled
                                    apply(enabled)
                                }
                            },
                        )
                    }
                }
            }

            addDives.Dialogs()
            backup.Dialogs()
        }
    }
}

/** The page a piece of gear is shown on, apart from the section's own; see [PAGES]. */
private const val GEAR_PAGE = "/gear"

/** Each section's pages, as keys for their saved state after the section's name. */
private val PAGES = listOf("", GEAR_PAGE)

/** The breadcrumb label for [screen]. */
private fun screenLabel(screen: Screen, container: AppContainer): String = when (screen) {
    is Screen.DiveDetail -> diveLabel(container.dives.getDive(screen.diveId))
    is Screen.Country -> container.sites.country(screen.countryId)?.name ?: "Country"
    is Screen.Place -> container.sites.place(screen.placeId)?.name ?: "Place"
    is Screen.Site -> container.sites.site(screen.siteId)?.name ?: "Site"
    is Screen.Buddy -> container.buddies.get(screen.buddyId)?.name ?: "Buddy"
    is Screen.Tag -> container.tags.get(screen.tagId)?.name ?: "Tag"
    is Screen.Gear -> container.gear.get(screen.gearId)?.name ?: "Gear"
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
    is Screen.Gear -> container.gear.getFlow(screen.gearId).map { it?.name ?: "Gear" }
    else -> flowOf(fixedLabel(screen))
}

private fun diveLabel(dive: Dive?): String = dive?.number?.let { "Dive #$it" } ?: "Dive"

private fun fixedLabel(screen: Screen): String = when (screen) {
    is Screen.ToolPage -> screen.tool.label
    Screen.Attributions -> "Attributions"
    else -> "Edit"
}
