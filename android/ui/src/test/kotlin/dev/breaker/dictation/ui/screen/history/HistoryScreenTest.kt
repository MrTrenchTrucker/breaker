package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the history screen draws for a state: the empty line, the rows in the order given,
 * the tag and the placeholder, the actions on each row, the Undo block that replaces a row,
 * when Load more and the limit note appear, and that every chrome word is a [HistoryTexts]
 * constant and every colour a palette slot.
 */
class HistoryScreenTest {
    private val screen = HistoryScreen()

    private fun row(
        id: String,
        text: String = "words $id",
        source: TranscriptionSource = TranscriptionSource.LOCAL,
    ) = HistoryRow(id, "TIME-$id", source, text)

    private fun state(
        rows: List<HistoryRow> = emptyList(),
        pending: Set<String> = emptySet(),
        shown: Int = PAGE_SIZE,
        fullPage: Boolean = false,
        listFailed: Boolean = false,
    ) = HistoryState(rows, pending, shown, fullPage, listFailed)

    private fun render(s: HistoryState = state(), notice: HistoryNotice? = null): Screen = screen.render(s, notice)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun node(s: Screen, id: String): Node? = nodesOf(s).firstOrNull { it.id == id }

    private fun label(s: Screen, id: String): Label =
        node(s, id) as? Label ?: throw AssertionError("history: no Label with id $id in ${nodesOf(s).map { it.id }}")

    private fun action(s: Screen, id: String): Action =
        node(s, id) as? Action ?: throw AssertionError("history: no Action with id $id in ${nodesOf(s).map { it.id }}")

    @Test
    fun `an empty list shows the empty line and no row`() {
        val s = render(state())
        assertEquals("history: empty line", HistoryTexts.EMPTY, label(s, "history.empty").text)
        assertTrue("history: no row when the list is empty", nodesOf(s).none { it.id.startsWith("history.row.") })
        assertNull("history: no Load more on an empty list", node(s, "history.more"))
    }

    @Test
    fun `rows are drawn in the order the port gave them`() {
        val s = render(state(rows = listOf(row("c"), row("a"), row("b"))))
        assertEquals(
            "history: order",
            listOf("history.title", "history.row.c", "history.row.a", "history.row.b"),
            s.nodes.map { it.id },
        )
    }

    @Test
    fun `each source has its own tag`() {
        val local = row("l", source = TranscriptionSource.LOCAL)
        val server = row("s", source = TranscriptionSource.SERVER)
        val s = render(state(rows = listOf(local, server)))
        assertEquals("history: local tag", HistoryTexts.TAG_LOCAL, label(s, "history.row.l.tag").text)
        assertEquals("history: server tag", HistoryTexts.TAG_SERVER, label(s, "history.row.s.tag").text)
    }

    @Test
    fun `a blank or spaces-only text shows the placeholder and any other text is shown as it is`() {
        val s = render(state(rows = listOf(row("e", text = ""), row("w", text = "   "), row("t", text = "hello"))))
        assertEquals("history: empty text", HistoryTexts.BLANK_TEXT, label(s, "history.row.e.text").text)
        assertEquals("history: spaces only", HistoryTexts.BLANK_TEXT, label(s, "history.row.w.text").text)
        assertEquals("history: real text", "hello", label(s, "history.row.t.text").text)
    }

    @Test
    fun `every row carries its time, a copy action and a delete action for its own id`() {
        val s = render(state(rows = listOf(row("a"), row("b"))))
        for (id in listOf("a", "b")) {
            assertEquals("history: time of $id", "TIME-$id", label(s, "history.row.$id.time").text)
            assertEquals(
                "history: copy of $id",
                ScreenIntent.History(HistoryActions.COPY, id),
                action(s, "history.row.$id.copy").intent,
            )
            assertEquals(
                "history: delete of $id",
                ScreenIntent.History(HistoryActions.DELETE, id),
                action(s, "history.row.$id.delete").intent,
            )
        }
    }

    @Test
    fun `delete is drawn as destructive and copy is not`() {
        val s = render(state(rows = listOf(row("a"))))
        assertEquals("history: delete emphasis", Emphasis.DESTRUCTIVE, action(s, "history.row.a.delete").emphasis)
        assertEquals("history: copy emphasis", Emphasis.SECONDARY, action(s, "history.row.a.copy").emphasis)
    }

    @Test
    fun `a row waiting for its Undo window is replaced in place by the deleted block`() {
        val s = render(state(rows = listOf(row("a"), row("b"), row("c")), pending = setOf("b")))
        assertEquals(
            "history: order with the block in place",
            listOf("history.title", "history.row.a", "history.undo.b", "history.row.c"),
            s.nodes.map { it.id },
        )
        assertNull("history: the row itself is gone", node(s, "history.row.b"))
        assertEquals("history: deleted label", HistoryTexts.NOTICE_DELETED, label(s, "history.undo.b.label").text)
        val undo = action(s, "history.undo.b.button")
        assertEquals("history: undo text", HistoryTexts.BUTTON_UNDO, undo.text)
        assertEquals("history: undo intent", ScreenIntent.History(HistoryActions.UNDO, "b"), undo.intent)
    }

    @Test
    fun `Load more appears only after a full page and below the cap`() {
        val rows = listOf(row("a"))
        assertNull("history: a short page has no Load more", node(render(state(rows, shown = 50)), "history.more"))
        val afterFull = action(render(state(rows, shown = 50, fullPage = true)), "history.more")
        assertEquals("history: Load more text", HistoryTexts.BUTTON_LOAD_MORE, afterFull.text)
        assertEquals("history: Load more intent", ScreenIntent.History(HistoryActions.MORE), afterFull.intent)
        val midway = render(state(rows, shown = 250, fullPage = true))
        assertTrue("history: Load more below the cap", node(midway, "history.more") is Action)
    }

    @Test
    fun `the limit note replaces Load more at the cap`() {
        val s = render(state(listOf(row("a")), shown = ROW_CAP, fullPage = true))
        assertEquals("history: limit note", HistoryTexts.LIMIT_NOTE, label(s, "history.limit").text)
        assertNull("history: no Load more at the cap", node(s, "history.more"))
    }

    @Test
    fun `a short last page at the cap shows neither the limit note nor Load more`() {
        val s = render(state(listOf(row("a")), shown = ROW_CAP, fullPage = false))
        assertNull("history: no limit note on a short last page", node(s, "history.limit"))
        assertNull("history: no Load more on a short last page", node(s, "history.more"))
    }

    @Test
    fun `a notice is drawn above the rows in the colour of its kind`() {
        val copied = render(state(listOf(row("a"))), HistoryNotice.COPIED)
        assertEquals("history: copied text", HistoryTexts.NOTICE_COPIED, label(copied, "history.notice").text)
        assertEquals("history: copied colour", PaletteSlot.STATE_SENT, label(copied, "history.notice").color)
        assertEquals("history: notice position", "history.notice", copied.nodes[1].id)

        val failed = render(state(listOf(row("a"))), HistoryNotice.DELETE_FAILED)
        assertEquals("history: failed text", HistoryTexts.NOTICE_DELETE_FAILED, label(failed, "history.notice").text)
        assertEquals("history: failed colour", PaletteSlot.DANGER, label(failed, "history.notice").color)
    }

    @Test
    fun `a failed first read shows the list failure sentence in place of the empty line and no rows`() {
        val s = render(state(listFailed = true))
        assertEquals("history: failure sentence", HistoryTexts.NOTICE_LIST_FAILED, label(s, "history.notice").text)
        assertNull("history: no empty line after a failed first read", node(s, "history.empty"))
        assertTrue("history: no rows after a failed first read", nodesOf(s).none { it.id.startsWith("history.row.") })
    }

    @Test
    fun `every colour is a palette slot and every chrome word is a HistoryTexts constant`() {
        val words = setOf(
            HistoryTexts.TITLE,
            HistoryTexts.EMPTY,
            HistoryTexts.TAG_LOCAL,
            HistoryTexts.TAG_SERVER,
            HistoryTexts.BUTTON_COPY,
            HistoryTexts.BUTTON_DELETE,
            HistoryTexts.BUTTON_LOAD_MORE,
            HistoryTexts.BLANK_TEXT,
            HistoryTexts.NOTICE_COPIED,
            HistoryTexts.NOTICE_COPY_FAILED,
            HistoryTexts.NOTICE_DELETE_FAILED,
            HistoryTexts.NOTICE_DELETED,
            HistoryTexts.NOTICE_LIST_FAILED,
            HistoryTexts.BUTTON_UNDO,
            HistoryTexts.LIMIT_NOTE,
        )
        val s = render(
            state(
                rows = listOf(row("a"), row("b", text = ""), row("c", source = TranscriptionSource.SERVER)),
                pending = setOf("c"),
                shown = 50,
                fullPage = true,
            ),
            HistoryNotice.COPY_FAILED,
        )
        val userText = setOf("history.row.a.text", "history.row.b.text")
        val chrome = nodesOf(s)
            .filter { it is Label || it is Action }
            .filterNot { it.id in userText || it.id.endsWith(".time") }
        for (n in chrome) {
            val text = if (n is Label) n.text else (n as Action).text
            assertTrue("history: '$text' at ${n.id} is not a HistoryTexts constant", text in words)
        }
        for (n in nodesOf(s).filterIsInstance<Label>()) {
            assertTrue("history: colour of ${n.id} is a palette slot", n.color in PaletteSlot.entries)
        }
    }

}
