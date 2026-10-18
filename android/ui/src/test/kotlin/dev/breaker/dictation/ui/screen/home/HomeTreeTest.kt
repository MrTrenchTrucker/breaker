package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Dot
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.MenuIcon
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.Switch
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TouchGap
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home page as the three Home frames draw it, checked on the tree: the top bar is one row with the
 * menu icon first and the wordmark centred; the accent rule and the page stripes are top-level nodes;
 * the status card has the accent edge, its title row with the caps word and the switch, and its rows as
 * two-column rows; the mic is a box with its words below it after a large gap.
 */
class HomeTreeTest {
    private val screen = HomeScreen(TimestampFormat { "at $it" })

    private val full = HomeState(
        switchOn = true,
        modelReady = true,
        lastDictationAt = 1L,
        lastTranscript = "Running about twenty minutes late,",
        transcriptTime = "06:42",
    )

    private fun top(state: HomeState): List<Node> = screen.render(state).nodes

    private inline fun <reified T : Node> nodeOf(state: HomeState, id: String): T =
        top(state).flatMap { it.flatten() }.filterIsInstance<T>().first { it.id == id }

    @Test
    fun `the top bar is one row with the menu icon first and the wordmark centred`() {
        val bar = nodeOf<Box>(full, HomeNodeIds.TOP_BAR)
        assertTrue("home: the top bar is a row", bar.horizontal)
        assertTrue("home: the menu icon is the first child of the bar", bar.children.first() is MenuIcon)
        assertEquals("home: the wordmark is centred", TextAlign.CENTER, nodeOf<Label>(full, HomeNodeIds.TOP_BAR_WORD).align)
    }

    @Test
    fun `the top bar ends in a touch gap that balances the menu icon, so the wordmark sits at the centre`() {
        val bar = nodeOf<Box>(full, HomeNodeIds.TOP_BAR)
        assertEquals("home: the bar has three children", 3, bar.children.size)
        assertEquals("home: the last child is the gap", HomeNodeIds.TOP_BAR_GAP, bar.children.last().id)
        assertTrue("home: the last child is a touch gap", bar.children.last() is TouchGap)
    }

    @Test
    fun `the accent rule is a top-level node drawn right under the top bar`() {
        val rule = top(full).filterIsInstance<Rule>().single()
        assertEquals("home: the rule's id", HomeNodeIds.TOP_BAR_TRIM, rule.id)
        assertEquals("home: the rule is accent", PaletteSlot.ACCENT, rule.color)
        val bar = top(full).indexOfFirst { it.id == HomeNodeIds.TOP_BAR }
        assertEquals("home: the rule follows the top bar", bar + 1, top(full).indexOf(rule))
    }

    @Test
    fun `the status card has the accent edge, the large pad and the small gap`() {
        val card = nodeOf<Box>(full, HomeNodeIds.CARD)
        assertEquals("home: the card's accent edge", PaletteSlot.ACCENT, card.edge)
        assertEquals("home: the card's pad is L", Space.L, card.pad)
        assertEquals("home: the card's gap is S", Space.S, card.gap)
        assertEquals("home: the card's fill is the surface", PaletteSlot.SURFACE, card.background)
        assertEquals("home: the card holds the title row and three rows", 4, card.children.size)
    }

    @Test
    fun `the title row holds the caps word upright and the switch after it`() {
        val title = nodeOf<Box>(full, "home.card.title")
        assertTrue("home: the title row is a row", title.horizontal)
        val word = title.children[0] as Label
        assertTrue("home: the title word is in capitals", word.caps)
        assertFalse("home: the title word is upright", word.italic)
        assertEquals("home: the title word is the display role", TypeRole.DISPLAY, word.role)
        assertTrue("home: the switch comes after the word", title.children[1] is Switch)
    }

    @Test
    fun `each status row is a two-column row with the muted key first`() {
        for (row in listOf("home.card.row.model", "home.card.row.server", "home.card.row.dictation")) {
            val cells = nodeOf<Box>(full, row)
            assertTrue("home: $row is a row", cells.horizontal)
            val key = cells.children[0] as Label
            assertEquals("home: $row key is muted", PaletteSlot.TEXT_MUTED, key.color)
        }
    }

    @Test
    fun `the model and server values show an accent square before their word, and the dictation value none`() {
        for (row in listOf("home.card.row.model", "home.card.row.server")) {
            val cell = nodeOf<Box>(full, "$row.cell")
            assertTrue("home: $row value cell is a row", cell.horizontal)
            assertEquals("home: $row square is the accent", PaletteSlot.ACCENT, (cell.children[0] as Dot).color)
            assertEquals("home: $row word follows the square", "$row.value", cell.children[1].id)
        }
        assertEquals("home: only the two dotted rows carry a square", 2, top(full).flatMap { it.flatten() }.filterIsInstance<Dot>().size)
        assertFalse("home: the dictation value has no square", ids(full).contains("home.card.row.dictation.dot"))
    }

    private fun ids(state: HomeState): Set<String> = top(state).flatMap { it.flatten() }.map { it.id }.toSet()

    @Test
    fun `the last dictation row carries only the time value, in mono, with no square`() {
        val row = nodeOf<Box>(full, "home.card.row.dictation")
        assertEquals("home: the dictation row has a key and a value only", 2, row.children.size)
        assertEquals("home: the dictation value is mono", TypeRole.MONO, nodeOf<Label>(full, "home.card.row.dictation.value").role)
    }

    @Test
    fun `the mic is a box with no words of its own, and its words come after it`() {
        val mic = nodeOf<Box>(full, HomeNodeIds.MIC)
        assertTrue("home: the mic box holds no words", mic.children.isEmpty())
        val order = top(full)
        val micAt = order.indexOfFirst { it.id == HomeNodeIds.MIC }
        assertEquals("home: the gap above the mic is XL", Space.XL, (order[micAt - 1] as Spacer).size)
        val words = nodeOf<Label>(full, HomeNodeIds.MIC_WORD)
        assertTrue("home: the words come after the mic", order.indexOf(words) > micAt)
        assertTrue("home: TAP TO TALK is in capitals", words.caps)
        assertEquals("home: TAP TO TALK is centred", TextAlign.CENTER, words.align)
    }

    @Test
    fun `the hint is centred and muted`() {
        val hint = nodeOf<Label>(full, HomeNodeIds.MIC_HINT)
        assertEquals("home: the hint is centred", TextAlign.CENTER, hint.align)
        assertEquals("home: the hint is muted", PaletteSlot.TEXT_MUTED, hint.color)
    }

    @Test
    fun `the page stripes are the last top-level node and carry the four-bar Home look`() {
        val last = top(full).last()
        assertEquals("home: the last node is the page stripes", HomeNodeIds.STRIPES, last.id)
        assertTrue("home: the last node is a bottom stripes node", last is BottomStripes)
        assertFalse("home: the page stripes are the Home look", (last as BottomStripes).plain)
    }

    @Test
    fun `the gaps are named by their scale names`() {
        assertEquals("home: the gap above the card is M", Space.M, nodeOf<Spacer>(full, HomeNodeIds.GAP_TOP).size)
        assertEquals("home: the gap between the mic and its words is S", Space.S, nodeOf<Spacer>(full, HomeNodeIds.GAP_WORDS).size)
        assertEquals("home: the gap above the transcript is L", Space.L, nodeOf<Spacer>(full, HomeNodeIds.GAP_TRANSCRIPT).size)
    }

    @Test
    fun `the menu icon is described by the menu word and the switch by its state word`() {
        val menu = nodeOf<Box>(full, HomeNodeIds.TOP_BAR).children.first() as MenuIcon
        assertEquals("home: the menu icon word", HomeTexts.MENU_BUTTON, menu.description)
        assertEquals("home: the on switch word", HomeTexts.SWITCH_ON, nodeOf<Switch>(full, HomeNodeIds.SWITCH).description)
        assertEquals("home: the off switch word", HomeTexts.SWITCH_OFF, nodeOf<Switch>(HomeState(), HomeNodeIds.SWITCH).description)
    }

    @Test
    fun `the status square has a gap of XS before its word`() {
        for (row in listOf("home.card.row.model", "home.card.row.server")) {
            assertEquals("home: $row cell gap", Space.XS, nodeOf<Box>(full, "$row.cell").gap)
        }
    }
}
