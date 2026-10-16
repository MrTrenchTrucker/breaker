package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
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

    private val screen = HomeScreen()

    /** A state where every row and the transcript box have something to show. */
    private val full = HomeState(
        switchOn = true,
        modelReady = true,
        lastDictation = "06:42",
        lastTranscript = "Running about twenty minutes late,",
        transcriptTime = "06:42",
    )

    private fun tree(state: HomeState): List<Node> = screen.render(state).nodes.flatMap { it.flatten() }

    private fun ids(state: HomeState): Set<String> = tree(state).map { it.id }.toSet()

    private fun box(state: HomeState, id: String): Box =
        tree(state).filterIsInstance<Box>().first { it.id == id }

    private fun label(state: HomeState, id: String): Label =
        tree(state).filterIsInstance<Label>().first { it.id == id }

    private fun action(state: HomeState, id: String): Action =
        tree(state).filterIsInstance<Action>().first { it.id == id }

    /** A failure means a node the art draws is missing, or carries the wrong slot or word. */
    @Test
    fun `the closed screen carries every node id with its slot and text`() {
        assertEquals(
            "home: every node id the art's parts become",
            setOf(
                "home.background",
                "home.topbar", "home.topbar.menu", "home.topbar.word", "home.topbar.trim",
                "home.card", "home.card.lip", "home.card.word", "home.card.switch",
                "home.card.row.model.key", "home.card.row.model.value",
                "home.card.row.server.key", "home.card.row.server.value",
                "home.card.row.dictation.key", "home.card.row.dictation.value",
                "home.mic", "home.mic.word", "home.mic.hint",
                "home.transcript", "home.transcript.word", "home.transcript.time",
                "home.transcript.text", "home.transcript.copy",
                "home.stripe.1", "home.stripe.2", "home.stripe.3", "home.stripe.4",
            ),
            ids(full),
        )
        assertEquals("home: the page background", PaletteSlot.BACKGROUND, box(full, "home.background").background)
        assertEquals("home: the top bar", PaletteSlot.BACKGROUND, box(full, "home.topbar").background)
        assertEquals("home: the bar's accent rule", PaletteSlot.ACCENT, box(full, "home.topbar.trim").background)
        assertEquals("home: the card", PaletteSlot.SURFACE, box(full, "home.card").background)
        assertEquals("home: the card's accent lip", PaletteSlot.ACCENT, box(full, "home.card.lip").background)
        // the mic's slot follows the switch: the on mic is the accent glow, the off mic the flat face
        assertEquals("home: the on mic", PaletteSlot.ACCENT, box(full, "home.mic").background)
        assertEquals("home: the off mic", PaletteSlot.TEXT_MUTED, box(HomeState(), "home.mic").background)
        assertEquals("home: the transcript box", PaletteSlot.SURFACE, box(full, "home.transcript").background)
        assertEquals("home: the first stripe", PaletteSlot.ACCENT, box(full, "home.stripe.1").background)
        assertEquals("home: the second stripe", PaletteSlot.PRIMARY, box(full, "home.stripe.2").background)
        assertEquals("home: the third stripe", PaletteSlot.ACCENT, box(full, "home.stripe.3").background)
        assertEquals("home: the fourth stripe", PaletteSlot.PRIMARY, box(full, "home.stripe.4").background)
        assertEquals("home: the top bar word", HomeTexts.LOGO, label(full, "home.topbar.word").text)
        assertEquals("home: the card word", HomeTexts.SWITCH_ON, label(full, "home.card.word").text)
        assertEquals("home: the mic word", HomeTexts.TAP_TO_TALK, label(full, "home.mic.word").text)
        assertEquals("home: the mic hint", HomeTexts.TAP_HINT, label(full, "home.mic.hint").text)
        assertEquals("home: the transcript heading", HomeTexts.LAST_TRANSCRIPT, label(full, "home.transcript.word").text)
        assertEquals("home: the transcript time", "06:42", label(full, "home.transcript.time").text)
        assertEquals("home: the transcript text", "Running about twenty minutes late,", label(full, "home.transcript.text").text)
        assertEquals("home: the copy word", HomeTexts.BUTTON_COPY, action(full, "home.transcript.copy").text)
        assertEquals("home: the switch intent", ScreenIntent.Home(HomeActions.SWITCH), action(full, "home.card.switch").intent)
    }

    /** A failure means the card or the switch reads the wrong state word. */
    @Test
    fun `the card and the switch read the on word when the switch is on and the off word when not`() {
        assertEquals("home: the on card word", HomeTexts.SWITCH_ON, label(full, "home.card.word").text)
        assertEquals("home: the on switch word", HomeTexts.SWITCH_ON, action(full, "home.card.switch").text)
        val quiet = HomeState()
        assertEquals("home: the off card word", HomeTexts.SWITCH_OFF, label(quiet, "home.card.word").text)
        assertEquals("home: the off switch word", HomeTexts.SWITCH_OFF, action(quiet, "home.card.switch").text)
    }

    /** A failure means a status row shows a value the words do not rule. */
    @Test
    fun `the three status rows show the ruled values`() {
        assertEquals("home: the model key", HomeTexts.HEADING_MODEL, label(full, "home.card.row.model.key").text)
        assertEquals("home: the model value", HomeTexts.STATUS_MODEL_READY, label(full, "home.card.row.model.value").text)
        assertEquals("home: the server key", HomeTexts.TAG_SERVER, label(full, "home.card.row.server.key").text)
        assertEquals("home: the server value", HomeTexts.STATUS_NOT_AVAILABLE, label(full, "home.card.row.server.value").text)
        assertEquals("home: the dictation key", HomeTexts.STATUS_LAST_DICTATION, label(full, "home.card.row.dictation.key").text)
        assertEquals("home: the dictation value", "06:42", label(full, "home.card.row.dictation.value").text)
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
            HomeTexts.STATUS_NOT_AVAILABLE,
            label(reachable, "home.card.row.server.value").text,
        )
        val unreachable = HomeState(serverReachable = false)
        assertTrue(
            "home: the server value is drawn when not reachable",
            ids(unreachable).contains("home.card.row.server.value"),
        )
        assertEquals(
            "home: the server value when not reachable",
            HomeTexts.STATUS_NOT_AVAILABLE,
            label(unreachable, "home.card.row.server.value").text,
        )
    }

    /** A failure means the copy button is missing, disabled or reports the wrong tap. */
    @Test
    fun `the copy action is present and enabled while there is a transcript`() {
        val copy = action(full, "home.transcript.copy")
        assertEquals("home: the copy word", HomeTexts.BUTTON_COPY, copy.text)
        assertTrue("home: the copy is enabled", copy.enabled)
        assertEquals("home: the copy intent", ScreenIntent.Home(HomeActions.COPY), copy.intent)
        assertFalse("home: no copy when the box is empty", ids(HomeState()).contains("home.transcript.copy"))
    }

    /** A failure means the drawer's nodes are wrong, or show while the drawer is shut. */
    @Test
    fun `the open menu adds the drawer's nodes and the closed screen has none of them`() {
        val open = HomeState(menuOpen = true)
        assertEquals(
            "home: the drawer's ids",
            setOf(
                "home.drawer.dim", "home.drawer.panel", "home.drawer.word",
                "home.drawer.item.history", "home.drawer.item.settings",
                "home.drawer.item.how", "home.drawer.item.about",
                "home.drawer.stripe.1", "home.drawer.stripe.2", "home.drawer.stripe.3",
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
        assertEquals("home: the first drawer stripe", PaletteSlot.ACCENT, box(open, "home.drawer.stripe.1").background)
        assertEquals("home: the second drawer stripe", PaletteSlot.PRIMARY, box(open, "home.drawer.stripe.2").background)
        assertEquals("home: the third drawer stripe", PaletteSlot.ACCENT, box(open, "home.drawer.stripe.3").background)
        assertEquals(emptySet<String>(), ids(HomeState()).filter { it.startsWith("home.drawer") }.toSet())
    }

    /** A failure means the empty box shows something other than the ruled empty word. */
    @Test
    fun `the empty transcript box shows the empty word and no time`() {
        val quiet = HomeState()
        assertEquals("home: the empty word", HomeTexts.EMPTY_BOX, label(quiet, "home.transcript.text").text)
        assertFalse("home: no time when there is none", ids(quiet).contains("home.transcript.time"))
    }
}
