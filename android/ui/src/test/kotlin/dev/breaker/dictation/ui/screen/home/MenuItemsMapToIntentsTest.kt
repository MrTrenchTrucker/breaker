package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.about.AboutTexts
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.HistoryTexts
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.screen.howitworks.HowItWorksTexts
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four rows of the menu drawer, and the intent each one reports.
 *
 * The drawer is read from the real Home tree with the menu open. A tap is run through the Home intent
 * handler, which is plain Kotlin. The open request itself is made by the host view, which needs the
 * Android framework, so the host is read as text: each menu intent maps to one callback, and that
 * callback is called once. The rules return the problems they find, and each sample is a change to the
 * real tree or to the real host text that one rule must report.
 */
class MenuItemsMapToIntentsTest {
    private val screen = HomeScreen(TimestampFormat { it.toString() })
    private val open = HomeState(menuOpen = true)

    /** The rows in the order of the picture: the row id, the words of the screen it opens, and its intent. */
    private val rows: List<Triple<String, String, ScreenIntent>> = listOf(
        Triple(HomeNodeIds.DRAWER_HISTORY, HistoryTexts.TITLE, ScreenIntent.Home(HomeActions.HISTORY)),
        Triple(HomeNodeIds.DRAWER_SETTINGS, SettingsScreen.TITLE, ScreenIntent.Home(HomeActions.SETTINGS)),
        Triple(HomeNodeIds.DRAWER_HOW, HowItWorksTexts.TITLE, ScreenIntent.Home(HomeActions.HOW_IT_WORKS)),
        Triple(HomeNodeIds.DRAWER_ABOUT, AboutTexts.TITLE, ScreenIntent.Home(HomeActions.ABOUT)),
    )

    private val hostText: String by lazy { mainSourceOf("render/HomeHostView.kt") }

    private val hostMapping = Regex("""HomeActions\.([A-Z_]+) -> (on[A-Za-z]+)\(\)""")

    private fun drawnNodes(): List<Node> = screen.render(open).nodes

    /** The text rows of the drawer panel in [nodes], in order. */
    private fun rowsOf(nodes: List<Node>): List<Action> {
        val panel = nodes.firstOrNull { it.id == HomeNodeIds.DRAWER_PANEL } as? Box ?: return emptyList()
        return panel.flatten().filterIsInstance<Action>().filter { it.kit == KitLook.TEXT }
    }

    /** [nodes] with the children of the drawer panel changed by [change]. */
    private fun withPanel(nodes: List<Node>, change: (List<Node>) -> List<Node>): List<Node> =
        nodes.map { node ->
            if (node.id == HomeNodeIds.DRAWER_PANEL) (node as Box).let { it.copy(children = change(it.children)) } else node
        }

    /** [nodes] with the row [id] changed by [change]. */
    private fun changeRow(nodes: List<Node>, id: String, change: (Action) -> Action): List<Node> =
        withPanel(nodes) { children -> children.map { if (it is Action && it.id == id) change(it) else it } }

    private fun extraRow(): Action = Action(
        "home.drawer.item.extra",
        HistoryTexts.TITLE,
        intent = ScreenIntent.Home(HomeActions.HISTORY),
        kit = KitLook.TEXT,
    )

    /** The drawer rows are exactly the four of the picture, in order. */
    private fun orderProblems(nodes: List<Node>): List<String> {
        val ids = rowsOf(nodes).map { it.id }
        val expected = rows.map { it.first }
        return if (ids == expected) emptyList() else listOf("the drawer rows are $ids, expected $expected")
    }

    /** Each row reports the intent of its own screen, and no two rows report the same intent. */
    private fun intentProblems(nodes: List<Node>): List<String> {
        val byId = rowsOf(nodes).associateBy { it.id }
        val problems = rows.mapNotNull { (id, _, intent) ->
            val row = byId[id]
            when {
                row == null -> "the row $id is missing"
                row.intent != intent -> "the row $id reports ${row.intent}, expected $intent"
                else -> null
            }
        }
        val distinct = rowsOf(nodes).map { it.intent }.toSet().size
        return if (distinct == rowsOf(nodes).size) problems else problems + "the drawer rows share an intent"
    }

    /** Each row keeps the words of the screen it opens. */
    private fun wordProblems(nodes: List<Node>): List<String> {
        val byId = rowsOf(nodes).associateBy { it.id }
        return rows.mapNotNull { (id, words, _) ->
            val row = byId[id]
            when {
                row == null -> "the row $id is missing"
                row.text != words -> "the row $id says ${row.text}, expected $words"
                else -> null
            }
        }
    }

    /** A tap on each row closes the menu and the handler reports the intent of that row. */
    private fun tapProblems(expected: List<Triple<String, String, ScreenIntent>>): List<String> =
        expected.mapNotNull { (id, _, intent) ->
            val handler = HomeIntentHandler(screen)
            handler.show(open)
            val reported = handler.intentFor(id)
            val after = handler.tap(id)
            when {
                reported != intent -> "a tap on $id reports $reported, expected $intent"
                after.nodes.any { it.id == HomeNodeIds.DRAWER_PANEL } -> "a tap on $id leaves the menu open"
                else -> null
            }
        }

    /**
     * The host's menu mapping: one menu intent per row, and each callback it names is called once in the
     * host. [raw] is the host text as written; comments and literal text are removed before the count.
     */
    private fun hostProblems(raw: String, rowCount: Int): List<String> {
        val code = withoutCommentsAndStrings(raw)
        val pairs = hostMapping.findAll(code).map { it.groupValues[2] }.toList()
        val problems = mutableListOf<String>()
        if (pairs.size != rowCount) problems += "the host maps ${pairs.size} menu intents, expected $rowCount rows"
        for (callback in pairs) {
            val calls = Regex("""\b${callback}\(\)""").findAll(code).count()
            if (calls != 1) problems += "$callback is called $calls times in the host, expected 1"
        }
        return problems
    }

    /** [host] with the text [from] replaced by [to]; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    private fun edit(label: String, host: String, from: String, to: String): String {
        check(host.contains(from)) { "the sample \"$label\" has no text \"$from\" to change" }
        return host.replace(from, to)
    }

    @Test
    fun `the menu has exactly the four text rows of the picture, in order`() {
        val nodes = drawnNodes()
        assertEquals("the drawer rows", emptyList<String>(), orderProblems(nodes))
        assertTrue("a fifth row is reported", orderProblems(withPanel(nodes) { it + extraRow() }).isNotEmpty())
        assertTrue(
            "a dropped About row is reported",
            orderProblems(withPanel(nodes) { children -> children.filterNot { it.id == HomeNodeIds.DRAWER_ABOUT } }).isNotEmpty(),
        )
        assertEquals(
            "a rule added to the panel is not a row",
            emptyList<String>(),
            orderProblems(withPanel(nodes) { it + Rule("home.drawer.quiet", PaletteSlot.ACCENT) }),
        )
    }

    @Test
    fun `each menu row reports the intent of its own screen and no two rows share one`() {
        val nodes = drawnNodes()
        assertEquals("the intents", emptyList<String>(), intentProblems(nodes))
        val settings = changeRow(nodes, HomeNodeIds.DRAWER_SETTINGS) { it.copy(intent = ScreenIntent.Home(HomeActions.HISTORY)) }
        assertTrue("a Settings row that reports History is reported", intentProblems(settings).isNotEmpty())
        val about = changeRow(nodes, HomeNodeIds.DRAWER_ABOUT) { it.copy(intent = ScreenIntent.Home(HomeActions.HISTORY)) }
        assertTrue("an About row that reports History is reported", intentProblems(about).isNotEmpty())
        assertEquals(
            "a rule added to the panel leaves the intents alone",
            emptyList<String>(),
            intentProblems(withPanel(nodes) { it + Rule("home.drawer.quiet", PaletteSlot.ACCENT) }),
        )
    }

    @Test
    fun `each menu row keeps the words of the screen it opens`() {
        val nodes = drawnNodes()
        assertEquals("the words", emptyList<String>(), wordProblems(nodes))
        val about = changeRow(nodes, HomeNodeIds.DRAWER_ABOUT) { it.copy(text = HomeTexts.MENU_HOW_IT_WORKS) }
        assertTrue("an About row with the How it works words is reported", wordProblems(about).isNotEmpty())
        val history = changeRow(nodes, HomeNodeIds.DRAWER_HISTORY) { it.copy(text = SettingsScreen.TITLE) }
        assertTrue("a History row with the Settings words is reported", wordProblems(history).isNotEmpty())
        assertEquals(
            "a rule added to the panel leaves the words alone",
            emptyList<String>(),
            wordProblems(withPanel(nodes) { it + Rule("home.drawer.quiet", PaletteSlot.ACCENT) }),
        )
    }

    @Test
    fun `a tap on each menu row closes the menu and reports the intent of its own screen`() {
        assertEquals("the taps", emptyList<String>(), tapProblems(rows))
        val wrongIntent = rows.map { if (it.first == HomeNodeIds.DRAWER_SETTINGS) it.copy(third = ScreenIntent.Home(HomeActions.ABOUT)) else it }
        assertTrue("a Settings tap that reports About is reported", tapProblems(wrongIntent).isNotEmpty())
        val unknownRow = rows.map { if (it.first == HomeNodeIds.DRAWER_ABOUT) it.copy(first = "home.drawer.item.none") else it }
        assertTrue("a row the handler does not know is reported", tapProblems(unknownRow).isNotEmpty())
    }

    @Test
    fun `the home host asks for each menu screen once, and the menu intents match the rows`() {
        val rowCount = rowsOf(drawnNodes()).size
        assertEquals("the host", emptyList<String>(), hostProblems(hostText, rowCount))
        val fifth = edit("fifth menu intent", hostText, "HomeActions.ABOUT -> onAbout()", "HomeActions.ABOUT -> onAbout()\n            HomeActions.HIDE -> onHide()")
        assertTrue("a fifth menu intent is reported", hostProblems(fifth, rowCount).isNotEmpty())
        val twice = edit("About asked twice", hostText, "HomeActions.ABOUT -> onAbout()", "HomeActions.ABOUT -> onAbout().also { onAbout() }")
        assertTrue("an About request made twice is reported", hostProblems(twice, rowCount).isNotEmpty())
        val quiet = edit("comment", hostText, "private fun act(action: String) {", "// HomeActions.HIDE -> onHide()\n    private fun act(action: String) {")
        assertEquals("a comment naming a menu intent is not one", emptyList<String>(), hostProblems(quiet, rowCount))
    }
}
