package dev.breaker.dictation.ui.render

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutComments
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.HistoryNotice
import dev.breaker.dictation.ui.screen.history.HistoryRow
import dev.breaker.dictation.ui.screen.history.HistoryScreen
import dev.breaker.dictation.ui.screen.history.HistoryState
import dev.breaker.dictation.ui.screen.home.HomeNodeIds
import dev.breaker.dictation.ui.screen.home.HomeScreen
import dev.breaker.dictation.ui.screen.home.HomeState
import dev.breaker.dictation.ui.screen.settings.ModelRow
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * One padding place, read from the source and from the trees the real screens build.
 *
 * The host pads every screen's root by the system bars, the cutout and the gutter, in one
 * call. Three clauses keep that the only padding. (a) No screen description or host view
 * under screen/ or render/ adds padding of its own, beyond the calls named in the table
 * below, each with its reason. (b) On the real trees every Rule and every BottomStripes is
 * a top-level node, or a child of the drawer panel, and no top-level box that holds one
 * has a pad. (c) The page roots of the real trees carry no pad. Each clause runs first on
 * made-up samples that must be reported and samples that must stay quiet, then on the
 * real files.
 */

/** One setPadding call site: a layer file and the number of calls it may hold, with the reason. */
private data class PaddingSite(val file: String, val calls: Int, val reason: String)

/** The setPadding calls the layer may hold. A layer file that is not listed holds none. */
private val PADDING_SITES = listOf(
    PaddingSite(
        "render/HomeHostView.kt",
        3,
        "the host override of setPadding (its declaration), the forward of the host padding to the page, and the drawer panel top inset",
    ),
    PaddingSite(
        "render/ScreenRenderer.kt",
        1,
        "the one per-view padding of a box, applied from the box pad Space name",
    ),
    PaddingSite(
        "render/FirstRunPageViews.kt",
        0,
        "none: the first-run page takes its sides from the host gutter, so it adds no padding of its own",
    ),
)

/** A call of setPadding, or of one of its relatives. */
private val SET_PADDING = Regex("""\bsetPadding\w*\(""")

/** The render and screen sources of the module, each with its path. */
private val LAYER_SOURCES: List<Pair<String, String>> =
    MAIN_SOURCES.filter { (path, _) -> path.startsWith("render/") || path.startsWith("screen/") }

/** The setPadding calls in [text], with comments taken out. */
private fun paddingCallsIn(text: String): Int = SET_PADDING.findAll(withoutComments(text)).count()

/** The problem with one layer file: a count of calls that is not the pinned one. */
private fun paddingProblemsIn(path: String, text: String): List<String> {
    val pinned = PADDING_SITES.firstOrNull { it.file == path }?.calls ?: 0
    val found = paddingCallsIn(text)
    return if (found == pinned) emptyList() else listOf("$path: $found setPadding calls, pinned $pinned")
}

/** The ids of the page roots: the page background, the top bar and the two drawer layers. Every title bar is one too. */
private val PAGE_ROOT_IDS = setOf(HomeNodeIds.BACKGROUND, HomeNodeIds.TOP_BAR, HomeNodeIds.DRAWER_DIM, HomeNodeIds.DRAWER_PANEL)

private fun isRuleOrStripes(node: Node): Boolean = node is Rule || node is BottomStripes

/** The top-level nodes of [screen] that are page roots. */
private fun pageRoots(screen: Screen): List<Node> =
    screen.nodes.filter { it.id in PAGE_ROOT_IDS || it.id.endsWith(".titlebar") }

/** The top-level boxes of [screen] with a pad, as id and pad. */
private fun paddedTop(screen: Screen): List<Pair<String, Space>> =
    screen.nodes.filterIsInstance<Box>().filter { it.pad != Space.NONE }.map { it.id to it.pad }

/** The ids of the top-level rules of [screen]. */
private fun topRules(screen: Screen): List<String> = screen.nodes.filterIsInstance<Rule>().map { it.id }

/** The ids of the top-level stripes of [screen]. */
private fun topStripes(screen: Screen): List<String> = screen.nodes.filterIsInstance<BottomStripes>().map { it.id }

/**
 * The placement problems of [screen]: a rule or stripes inside a box other than the drawer
 * panel, and a top-level box with a pad that holds one.
 */
private fun placementProblems(screen: Screen): List<String> = buildList<String> {
    for (node in screen.nodes) {
        if (node !is Box) continue
        for (child in node.children) {
            if (isRuleOrStripes(child)) {
                if (node.id != HomeNodeIds.DRAWER_PANEL) add("${child.id} is a child of ${node.id}")
            } else if (child is Box) {
                child.flatten().filter { isRuleOrStripes(it) }.forEach { add("${it.id} is nested under ${node.id}") }
            }
        }
        if (node.pad != Space.NONE && node.children.any { isRuleOrStripes(it) }) add("${node.id} has pad and holds a rule or stripes")
    }
}

/** The page roots of [screen] that carry a pad, one message each. */
private fun pageRootPadProblems(screen: Screen): List<String> =
    pageRoots(screen).filterIsInstance<Box>().filter { it.pad != Space.NONE }.map { "${it.id} is a page root with pad ${it.pad}" }

/** One padded top-level box of a real tree, with the reason it is padded. */
private data class Padded(val id: String, val pad: Space, val reason: String)

private val HOME_CARDS = listOf(
    Padded(HomeNodeIds.CARD, Space.L, "the status card, padded inside the page"),
    Padded(HomeNodeIds.TRANSCRIPT, Space.L, "the shared transcript card, padded inside the page"),
)

/** The padded top-level boxes of each real tree. Each tree lists the boxes it is pinned to, and why. */
private val PADDED_IN_TREE: Map<String, List<Padded>> = mapOf(
    "home closed" to HOME_CARDS,
    "home open" to HOME_CARDS,
    "settings" to emptyList(),
    "settings not ready" to emptyList(),
    "history one row" to listOf(Padded("history.row.r1", Space.L, "one history row, the shared card padded inside the page")),
    "history copied" to listOf(Padded("history.notice.box", Space.M, "the copied notice box above the rows")),
)

private fun themeState(): ThemeState = ThemeState(stored = StoredThemeMode.SYSTEM, shown = ShownThemeMode.LIGHT, loaded = true)

private fun homeClosed(): Screen = HomeScreen().render(HomeState())

private fun homeOpen(): Screen = HomeScreen().render(HomeState(menuOpen = true))

private fun settingsReady(): Screen = SettingsScreen().render(AppSettings(), themeState(), null, ModelRow.READY)

private fun settingsNotReady(): Screen = SettingsScreen().render(AppSettings(), themeState(), null, ModelRow(ready = false, line = null))

private fun historyOneRow(): Screen = HistoryScreen().render(
    HistoryState(
        rows = listOf(HistoryRow("r1", "06:42", TranscriptionSource.LOCAL, "Running about twenty minutes late,")),
        pending = emptySet(),
        shown = 1,
        fullPage = false,
    ),
    null,
)

private fun historyCopied(): Screen = HistoryScreen().render(
    HistoryState(rows = emptyList(), pending = emptySet(), shown = 0, fullPage = false),
    HistoryNotice.COPIED,
)

class GutterRuleTest {
    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("padding rule: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("padding rule: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `each render and screen file holds exactly its pinned padding calls`() {
        val found = LAYER_SOURCES.flatMap { (path, text) -> paddingProblemsIn(path, text) }
        assertEquals("setPadding calls off the pinned table: $found", emptyList<String>(), found)
        val present = LAYER_SOURCES.map { it.first }.toSet()
        val missing = PADDING_SITES.map { it.file }.filterNot { it in present }
        assertEquals("pinned files that are not in the layer: $missing", emptyList<String>(), missing)
        assertEquals("the pinned total of setPadding calls", 4, PADDING_SITES.sumOf { it.calls })
    }

    @Test
    fun `a padding call added to a layer file is caught and a commented one is not`() {
        val twice = "view.setPadding(side, side, side, side)\nview.setPadding(side, side, side, side)\n"
        assertFires("a second call in the renderer", paddingProblemsIn("render/ScreenRenderer.kt", twice), "2 setPadding calls")
        assertFires("a call in a screen description", paddingProblemsIn("screen/home/HomeScreen.kt", "page.setPadding(a, a, a, a)\n"), "pinned 0")
        assertFires("a call in a new host file", paddingProblemsIn("render/NewHost.kt", "view.setPadding(side, side, side, side)\n"), "pinned 0")
        assertQuiet("a call named only in a comment", paddingProblemsIn("screen/home/HomeScreen.kt", "// setPadding(0, 0, 0, 0)\n"))
        assertQuiet("the one pinned call", paddingProblemsIn("render/ScreenRenderer.kt", "view.setPadding(side, side, side, side)\n"))
    }

    @Test
    fun `a rule or stripes inside a box, or a padded box holding one, is caught`() {
        val nested = Screen("s", "t", listOf(Box("x.box", PaletteSlot.BACKGROUND, children = listOf(Rule("x.rule", PaletteSlot.ACCENT)))))
        assertFires("a rule inside a box", placementProblems(nested), "is a child of x.box")
        val padded = Screen("s", "t", listOf(Box("x.pad", PaletteSlot.BACKGROUND, pad = Space.M, children = listOf(BottomStripes("x.stripe")))))
        assertFires("stripes in a padded box", placementProblems(padded), "has pad")
        val drawer = Screen("s", "t", listOf(Box(HomeNodeIds.DRAWER_PANEL, PaletteSlot.SURFACE, pad = Space.S, children = listOf(Rule("x.rule", PaletteSlot.ACCENT)))))
        assertFires("a padded drawer panel holding a rule", placementProblems(drawer), "has pad")
        assertQuiet("a rule and stripes at the top level", placementProblems(Screen("s", "t", listOf(Rule("x.rule", PaletteSlot.ACCENT), BottomStripes("x.stripe")))))
        val panel = Box(HomeNodeIds.DRAWER_PANEL, PaletteSlot.SURFACE, children = listOf(Rule("x.rule", PaletteSlot.ACCENT), BottomStripes("x.stripe", plain = true)))
        assertQuiet("a rule in the unpadded drawer panel", placementProblems(Screen("s", "t", listOf(panel))))
        assertQuiet("a padded box with no rule", placementProblems(Screen("s", "t", listOf(Box("x.card", PaletteSlot.SURFACE, pad = Space.L)))))
    }

    @Test
    fun `the real screens hold every rule and stripes at the top level, bar the drawer panel`() {
        for (screen in listOf(homeClosed(), homeOpen(), settingsReady(), settingsNotReady(), historyOneRow(), historyCopied())) {
            assertEquals("placement on ${screen.id}", emptyList<String>(), placementProblems(screen))
        }
        assertEquals("closed home: one top rule", listOf(HomeNodeIds.TOP_BAR_TRIM), topRules(homeClosed()))
        assertEquals("closed home: one top stripes", listOf(HomeNodeIds.STRIPES), topStripes(homeClosed()))
        assertEquals("open home: one top rule", 1, topRules(homeOpen()).size)
        assertEquals("open home: one top stripes", 1, topStripes(homeOpen()).size)
        assertEquals("settings: one top rule", 1, topRules(settingsReady()).size)
        assertEquals("settings: one top stripes", 1, topStripes(settingsReady()).size)
        assertEquals("history: one top rule", 1, topRules(historyOneRow()).size)
        assertEquals("history: one top stripes", 1, topStripes(historyOneRow()).size)
    }

    @Test
    fun `the drawer panel is the one box that holds a rule and stripes, and it is pinned`() {
        val panel = homeOpen().nodes.filterIsInstance<Box>().single { it.id == HomeNodeIds.DRAWER_PANEL }
        assertEquals(
            "the drawer panel holds its rule and its stripes",
            listOf(HomeNodeIds.DRAWER_RULE, HomeNodeIds.DRAWER_STRIPES),
            panel.children.filter { isRuleOrStripes(it) }.map { it.id },
        )
        assertEquals("the closed home has no drawer panel", emptyList<String>(), homeClosed().nodes.filter { it.id == HomeNodeIds.DRAWER_PANEL }.map { it.id })
    }

    @Test
    fun `the padded top level boxes of each real tree are the pinned table, with a reason each`() {
        val trees = mapOf(
            "home closed" to homeClosed(),
            "home open" to homeOpen(),
            "settings" to settingsReady(),
            "settings not ready" to settingsNotReady(),
            "history one row" to historyOneRow(),
            "history copied" to historyCopied(),
        )
        for ((name, screen) in trees) {
            val pinned = PADDED_IN_TREE.getValue(name).map { it.id to it.pad }
            assertEquals("padded top level boxes of $name", pinned, paddedTop(screen))
        }
        assertEquals("every real tree is pinned", trees.keys, PADDED_IN_TREE.keys)
    }

    @Test
    fun `a pad on a page root is caught and a page root without one is not`() {
        val background = Screen("s", "t", listOf(Box(HomeNodeIds.BACKGROUND, PaletteSlot.BACKGROUND, pad = Space.L)))
        assertFires("a padded page background", pageRootPadProblems(background), "is a page root with pad")
        val titleBar = Screen("s", "t", listOf(Box("x.titlebar", PaletteSlot.BACKGROUND, pad = Space.S)))
        assertFires("a padded title bar", pageRootPadProblems(titleBar), "is a page root with pad")
        assertQuiet("a plain page background", pageRootPadProblems(Screen("s", "t", listOf(Box(HomeNodeIds.BACKGROUND, PaletteSlot.BACKGROUND)))))
        assertQuiet("a padded card, which is not a page root", pageRootPadProblems(Screen("s", "t", listOf(Box("x.card", PaletteSlot.SURFACE, pad = Space.L)))))
    }

    @Test
    fun `the page roots of the real screens carry no pad of their own`() {
        for (screen in listOf(homeClosed(), homeOpen(), settingsReady(), settingsNotReady(), historyOneRow(), historyCopied())) {
            assertEquals("page roots of ${screen.id} with a pad", emptyList<String>(), pageRootPadProblems(screen))
        }
        assertEquals("closed home: the background and the top bar", 2, pageRoots(homeClosed()).size)
        assertEquals("open home: the background, the top bar and the two drawer layers", 4, pageRoots(homeOpen()).size)
        assertEquals("settings: its title bar", 1, pageRoots(settingsReady()).size)
        assertEquals("history: its title bar", 1, pageRoots(historyOneRow()).size)
    }
}
