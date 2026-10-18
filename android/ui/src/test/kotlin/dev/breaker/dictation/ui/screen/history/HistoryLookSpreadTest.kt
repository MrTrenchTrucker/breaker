package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spread rows of the History look: the card's button row and the Deleted row spread their
 * children to the two ends of the row, the header row keeps its own shape, and a card without
 * delete still puts its copy button at the start.
 */
class HistoryLookSpreadTest {
    private val row = HistoryRow("a", "TIME-a", TranscriptionSource.LOCAL, "words a")

    private fun screen(pending: Set<String> = emptySet()): Screen =
        HistoryScreen().render(HistoryState(listOf(row), pending, PAGE_SIZE, false), null)

    private fun boxOf(s: Screen, id: String): Box =
        s.nodes.flatMap { it.flatten() }.filterIsInstance<Box>().first { it.id == id }

    @Test
    fun `the button row spreads so that copy sits at the start and delete at the end`() {
        val buttons = boxOf(screen(), "history.row.a.buttons")
        assertTrue("history: buttons spread", buttons.spread)
        assertEquals(
            "history: button order",
            listOf("history.row.a.copy", "history.row.a.toggle", "history.row.a.delete"),
            buttons.children.map { it.id },
        )
    }

    @Test
    fun `a card without delete keeps copy first and still spreads its button row`() {
        val card = transcriptCard(
            prefix = "history.row.z",
            text = "words z",
            blankText = HistoryTexts.BLANK_TEXT,
            opened = false,
            copyIntent = ScreenIntent.History(HistoryActions.COPY, "z"),
            toggleIntent = ScreenIntent.History(HistoryActions.SHOW_ALL, "z"),
        )
        assertTrue("history: card buttons spread", card.buttons.spread)
        assertEquals("history: first button is copy", "history.row.z.copy", card.buttons.children.first().id)
        assertNull("history: no delete button", card.buttons.children.firstOrNull { it.id.endsWith(".delete") })
    }

    @Test
    fun `the deleted row spreads so that its label sits at the start and undo at the end`() {
        val undo = boxOf(screen(pending = setOf("a")), "history.undo.a")
        assertTrue("history: undo spread", undo.spread)
        assertEquals(
            "history: undo children",
            listOf("history.undo.a.label", "history.undo.a.button"),
            undo.children.map { it.id },
        )
    }

    @Test
    fun `the header row keeps its own shape and does not spread`() {
        val header = boxOf(screen(), "history.row.a.header")
        assertTrue("history: header is a row", header.horizontal)
        assertFalse("history: header is not spread", header.spread)
    }
}
