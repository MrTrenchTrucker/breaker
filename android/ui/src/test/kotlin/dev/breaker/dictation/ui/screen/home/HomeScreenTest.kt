package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.render.dropFinalPeriod
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.MenuIcon
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Switch
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's tree: every node id the art's parts become, and the slot and
 * the text each carries, checked with no device and no framework class.
 */
class HomeScreenTest {

    private val format = TimestampFormat { "at $it" }
    private val screen = HomeScreen(format)

    /** A state where every row and the transcript box have something to show. */
    private val full = HomeState(
        switchOn = true,
        modelReady = true,
        lastDictationAt = 1234567890L,
        lastTranscript = "Running about twenty minutes late,",
        transcriptTime = "06:42",
    )

    private fun tree(state: HomeState): List<Node> = screen.render(state).nodes.flatMap { it.flatten() }

    private fun ids(state: HomeState): Set<String> = tree(state).map { it.id }.toSet()

    private fun box(state: HomeState, id: String): Box =
        tree(state).filterIsInstance<Box>().first { it.id == id }

    private fun label(state: HomeState, id: String): Label =
        tree(state).filterIsInstance<Label>().first { it.id == id }

    private fun collapsible(state: HomeState, id: String): CollapsibleText =
        tree(state).filterIsInstance<CollapsibleText>().first { it.id == id }

    private fun action(state: HomeState, id: String): Action =
        tree(state).filterIsInstance<Action>().first { it.id == id }

    private fun switch(state: HomeState, id: String): Switch =
        tree(state).filterIsInstance<Switch>().first { it.id == id }

    private fun menuIcon(state: HomeState, id: String): MenuIcon =
        tree(state).filterIsInstance<MenuIcon>().first { it.id == id }

    /** A failure means a node the art draws is missing, or carries the wrong slot or word. */
    @Test
    fun `the closed screen carries every node id with its slot and text`() {
        assertEquals(
            "home: every node id the art's parts become",
            setOf(
                "home.background",
                "home.topbar", "home.topbar.menu", "home.topbar.word", "home.topbar.trim", "home.topbar.gap",
                "home.gap.top", "home.card", "home.card.title", "home.card.word", "home.card.switch",
                "home.card.row.model", "home.card.row.model.key", "home.card.row.model.cell", "home.card.row.model.dot", "home.card.row.model.value",
                "home.card.row.server", "home.card.row.server.key", "home.card.row.server.cell", "home.card.row.server.dot", "home.card.row.server.value",
                "home.card.row.dictation", "home.card.row.dictation.key", "home.card.row.dictation.value",
                "home.gap.mic", "home.mic", "home.gap.words", "home.mic.word", "home.mic.hint",
                "home.gap.transcript", "home.transcript", "home.transcript.header", "home.transcript.word", "home.transcript.time",
                "home.transcript.text", "home.transcript.buttons",
                "home.transcript.copy", "home.transcript.toggle",
                "home.stripe",
            ),
            ids(full),
        )
        assertEquals("home: the page background", PaletteSlot.BACKGROUND, box(full, "home.background").background)
        assertEquals("home: the top bar", PaletteSlot.BACKGROUND, box(full, "home.topbar").background)
        assertEquals("home: the bar's accent rule", PaletteSlot.ACCENT, tree(full).filterIsInstance<Rule>().first { it.id == "home.topbar.trim" }.color)
        assertEquals("home: the card", PaletteSlot.SURFACE, box(full, "home.card").background)
        assertEquals("home: the card's accent edge", PaletteSlot.ACCENT, box(full, "home.card").edge)
        // the mic is a slot with no background of its own (ADR-026)
        assertEquals("home: the mic slot", PaletteSlot.BACKGROUND, box(full, "home.mic").background)
        assertEquals("home: the off mic slot", PaletteSlot.BACKGROUND, box(HomeState(), "home.mic").background)
        assertEquals("home: the transcript box", PaletteSlot.SURFACE, box(full, "home.transcript").background)
        assertEquals("home: the page stripes are the four-bar Home look", false, tree(full).filterIsInstance<BottomStripes>().first().plain)
        assertEquals("home: the top bar word", HomeTexts.LOGO, label(full, "home.topbar.word").text)
        assertEquals("home: the card word", dropFinalPeriod(HomeTexts.SWITCH_ON), label(full, "home.card.word").text)
        assertEquals("home: the mic word", HomeTexts.TAP_TO_TALK, label(full, "home.mic.word").text)
        assertEquals("home: the mic hint", HomeTexts.TAP_HINT, label(full, "home.mic.hint").text)
        assertEquals("home: the transcript heading", HomeTexts.LAST_TRANSCRIPT, label(full, "home.transcript.word").text)
        assertEquals("home: the transcript time", "06:42", label(full, "home.transcript.time").text)
        assertEquals("home: the transcript text", "Running about twenty minutes late,", collapsible(full, "home.transcript.text").text)
        assertEquals("home: the copy word", HomeTexts.BUTTON_COPY, action(full, "home.transcript.copy").text)
        assertEquals("home: the toggle word", HomeTexts.BUTTON_SHOW_ALL, action(full, "home.transcript.toggle").text)
        assertEquals("home: the switch intent", ScreenIntent.Home(HomeActions.SWITCH), switch(full, "home.card.switch").intent)
        assertEquals("home: the menu icon intent", ScreenIntent.Home(HomeActions.MENU), menuIcon(full, "home.topbar.menu").intent)
    }

    /** A failure means the card or the switch reads the wrong state word. */
    @Test
    fun `the card word is drawn once and the switch carries the on state`() {
        assertEquals("home: the on card word", dropFinalPeriod(HomeTexts.SWITCH_ON), label(full, "home.card.word").text)
        assertTrue("home: the on switch is on", switch(full, "home.card.switch").on)
        assertEquals("home: the switch intent", ScreenIntent.Home(HomeActions.SWITCH), switch(full, "home.card.switch").intent)
        val quiet = HomeState()
        assertEquals("home: the off card word", dropFinalPeriod(HomeTexts.SWITCH_OFF), label(quiet, "home.card.word").text)
        assertFalse("home: the off switch is off", switch(quiet, "home.card.switch").on)
    }

    /** A failure means a status row shows a value the words do not rule. */
    @Test
    fun `the three status rows show the ruled values`() {
        assertEquals("home: the model key", HomeTexts.HEADING_MODEL, label(full, "home.card.row.model.key").text)
        assertEquals("home: the model value", HomeTexts.STATUS_MODEL_READY, label(full, "home.card.row.model.value").text)
        assertEquals("home: the server key", HomeTexts.TAG_SERVER, label(full, "home.card.row.server.key").text)
        assertEquals("home: the server value", HomeTexts.STATUS_NOT_SET, label(full, "home.card.row.server.value").text)
        assertEquals("home: the dictation key", HomeTexts.STATUS_LAST_DICTATION, label(full, "home.card.row.dictation.key").text)
        assertEquals("home: the dictation value", "at 1234567890", label(full, "home.card.row.dictation.value").text)
        val quiet = HomeState()
        assertEquals("home: the not-ready model", HomeTexts.STATUS_MODEL_MISSING, label(quiet, "home.card.row.model.value").text)
        assertFalse("home: no dictation value when there is none", ids(quiet).contains("home.card.row.dictation.value"))
    }

    /**
     * A failure means the server row's value is not the one fixed word in every
     * state: the app cannot know reachability: no probe exists, so the tree
     * carries no branch and no blank state for it.
     */
    @Test
    fun `the server row's one value is pinned in both states`() {
        val reachable = HomeState(serverReachable = true)
        assertTrue(
            "home: the server value is drawn when reachable",
            ids(reachable).contains("home.card.row.server.value"),
        )
        assertEquals(
            "home: the server value when reachable",
            HomeTexts.STATUS_NOT_SET,
            label(reachable, "home.card.row.server.value").text,
        )
        val unreachable = HomeState(serverReachable = false)
        assertTrue(
            "home: the server value is drawn when not reachable",
            ids(unreachable).contains("home.card.row.server.value"),
        )
        assertEquals(
            "home: the server value when not reachable",
            HomeTexts.STATUS_NOT_SET,
            label(unreachable, "home.card.row.server.value").text,
        )
    }

    /** A failure means the copy button is missing, disabled or reports the wrong tap. */
    @Test
    fun `the copy action is present and enabled`() {
        val copy = action(full, "home.transcript.copy")
        assertEquals("home: the copy word", HomeTexts.BUTTON_COPY, copy.text)
        assertTrue("home: the copy is enabled", copy.enabled)
        assertEquals("home: the copy intent", ScreenIntent.Home(HomeActions.COPY), copy.intent)
    }

    /** A failure means the toggle is missing or reads the wrong word. */
    @Test
    fun `the toggle reads Show all while closed and sends SHOW_ALL`() {
        val toggle = action(full, "home.transcript.toggle")
        assertEquals("home: the closed toggle word", HomeTexts.BUTTON_SHOW_ALL, toggle.text)
        assertEquals("home: the closed toggle intent", ScreenIntent.Home(HomeActions.SHOW_ALL), toggle.intent)
        assertFalse("home: the text is closed", collapsible(full, "home.transcript.text").opened)
    }

    /** A failure means the toggle does not flip when the text is opened. */
    @Test
    fun `the toggle reads Hide and sends HIDE once the text is open`() {
        val opened = full.copy(opened = true)
        val toggle = action(opened, "home.transcript.toggle")
        assertEquals("home: the open toggle word", HomeTexts.BUTTON_HIDE, toggle.text)
        assertEquals("home: the open toggle intent", ScreenIntent.Home(HomeActions.HIDE), toggle.intent)
        assertTrue("home: the text is open", collapsible(opened, "home.transcript.text").opened)
    }

    /** A failure means the transcript card offers a delete on the home screen. */
    @Test
    fun `the transcript card has copy and toggle but no delete`() {
        assertFalse("home: no delete on the home transcript card", ids(full).contains("home.transcript.delete"))
    }

    /** A failure means the drawer's nodes are wrong, or show while the drawer is shut. */
    @Test
    fun `the open menu adds the drawer's nodes and the closed screen has none of them`() {
        val open = HomeState(menuOpen = true)
        assertEquals(
            "home: the drawer's ids",
            setOf(
                "home.drawer.dim", "home.drawer.panel", "home.drawer.header", "home.drawer.word", "home.drawer.rule",
                "home.drawer.item.history", "home.drawer.item.settings",
                "home.drawer.item.how", "home.drawer.item.about",
                "home.drawer.stripe",
            ),
            ids(open).filter { it.startsWith("home.drawer") }.toSet(),
        )
        assertEquals("home: the dim", PaletteSlot.BACKGROUND, box(open, "home.drawer.dim").background)
        assertEquals("home: the panel", PaletteSlot.SURFACE, box(open, "home.drawer.panel").background)
        assertEquals("home: the drawer word", HomeTexts.LOGO, label(open, "home.drawer.word").text)
        assertEquals("home: the history item", HomeTexts.MENU_HISTORY, action(open, "home.drawer.item.history").text)
        assertEquals("home: the settings item", HomeTexts.MENU_SETTINGS, action(open, "home.drawer.item.settings").text)
        assertEquals("home: the how item", HomeTexts.MENU_HOW_IT_WORKS, action(open, "home.drawer.item.how").text)
        assertEquals("home: the about item", HomeTexts.MENU_ABOUT, action(open, "home.drawer.item.about").text)
        assertEquals("home: the history tap", ScreenIntent.Home(HomeActions.HISTORY), action(open, "home.drawer.item.history").intent)
        assertEquals("home: the settings tap", ScreenIntent.Home(HomeActions.SETTINGS), action(open, "home.drawer.item.settings").intent)
        assertEquals("home: the how tap", ScreenIntent.Home(HomeActions.HOW_IT_WORKS), action(open, "home.drawer.item.how").intent)
        assertEquals("home: the about tap", ScreenIntent.Home(HomeActions.ABOUT), action(open, "home.drawer.item.about").intent)
        assertTrue(
            "home: the drawer stripes are the plain three-bar look, their colours are read at the BleedMath seam",
            tree(open).filterIsInstance<BottomStripes>().first { it.id == HomeNodeIds.DRAWER_STRIPES }.plain,
        )
        assertEquals(emptySet<String>(), ids(HomeState()).filter { it.startsWith("home.drawer") }.toSet())
    }

    /** A failure means the empty box shows something other than the ruled empty word. */
    @Test
    fun `the empty transcript box shows the empty word and no time`() {
        val quiet = HomeState()
        assertEquals("home: the empty word", HomeTexts.EMPTY_BOX, collapsible(quiet, "home.transcript.text").text)
        assertFalse("home: no time when there is none", ids(quiet).contains("home.transcript.time"))
    }

    /** A failure means the line the host sends is not drawn, or a line node is drawn when there is none. */
    @Test
    fun `the line node carries the line the host sends and is absent when there is none`() {
        val withLine = HomeState(line = "A sentence from the app")
        assertEquals("home: the line's text", "A sentence from the app", label(withLine, "home.line").text)
        assertEquals("home: the line in the text slot", PaletteSlot.TEXT, label(withLine, "home.line").color)
        assertFalse("home: no line node when there is no line", ids(HomeState()).contains("home.line"))
    }

    /** A failure means the line node is not drawn between the notice and the stripes. */
    @Test
    fun `the line node sits after the notice and before the stripes`() {
        val order = screen.render(HomeState(notice = HomeTexts.MENU_NOT_BUILT, line = "A sentence from the app"))
            .nodes.map { it.id }
        val noticeIndex = order.indexOf(HomeNodeIds.NOTICE)
        val lineIndex = order.indexOf(HomeNodeIds.LINE)
        val stripeIndex = order.indexOf(HomeNodeIds.STRIPES)
        assertTrue("home: the line comes after the notice", lineIndex > noticeIndex)
        assertTrue("home: the line comes before the stripes", lineIndex < stripeIndex)
    }

    /** A failure means the mic slot carries a background of its own (ADR-026: it is a transparent slot). */
    @Test
    fun `the mic slot carries no background in any state`() {
        val recordingOn = HomeState(switchOn = true, recording = true)
        assertEquals("home: the mic slot while recording, switch on", PaletteSlot.BACKGROUND, box(recordingOn, "home.mic").background)
        val recordingOff = HomeState(switchOn = false, recording = true)
        assertEquals("home: the mic slot while recording, switch off", PaletteSlot.BACKGROUND, box(recordingOff, "home.mic").background)
        val idleOn = HomeState(switchOn = true, recording = false)
        assertEquals("home: the mic slot not recording, switch on", PaletteSlot.BACKGROUND, box(idleOn, "home.mic").background)
        val idleOff = HomeState(switchOn = false, recording = false)
        assertEquals("home: the mic slot not recording, switch off", PaletteSlot.BACKGROUND, box(idleOff, "home.mic").background)
    }
}
