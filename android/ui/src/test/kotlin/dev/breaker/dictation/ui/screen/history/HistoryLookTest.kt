package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The look the History screen draws, as facts of its node tree: the outlined shared card with its
 * header and button rows, the Copied box, the Deleted row, the empty state, LOAD MORE, and the order
 * of the title block and the stripes.
 */
class HistoryLookTest {
    private val row = HistoryRow("a", "TIME-a", TranscriptionSource.LOCAL, "words a")

    private fun screen(
        rows: List<HistoryRow> = listOf(row),
        pending: Set<String> = emptySet(),
        fullPage: Boolean = false,
        notice: HistoryNotice? = null,
    ): Screen = HistoryScreen().render(HistoryState(rows, pending, PAGE_SIZE, fullPage), notice)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun boxOf(s: Screen, id: String): Box = nodesOf(s).filterIsInstance<Box>().first { it.id == id }

    private fun labelOf(s: Screen, id: String): Label = nodesOf(s).filterIsInstance<Label>().first { it.id == id }

    private fun actionOf(s: Screen, id: String): Action = nodesOf(s).filterIsInstance<Action>().first { it.id == id }

    private fun spacerOf(s: Screen, id: String): Spacer = nodesOf(s).filterIsInstance<Spacer>().first { it.id == id }

    @Test
    fun `the shared card is an outlined accent box on a surface fill, padded at the large step`() {
        val card = boxOf(screen(), "history.row.a")
        assertEquals("history: card fill", PaletteSlot.SURFACE, card.background)
        assertEquals("history: card outline", PaletteSlot.ACCENT, card.outline)
        assertEquals("history: card pad", Space.L, card.pad)
        assertEquals("history: card gap", Space.S, card.gap)
    }

    @Test
    fun `the header row is horizontal with the time first and the source tag last`() {
        val header = boxOf(screen(), "history.row.a.header")
        assertTrue("history: header is a row", header.horizontal)
        assertEquals(
            "history: header children",
            listOf("history.row.a.time", "history.row.a.tag"),
            header.children.map { it.id },
        )
    }

    @Test
    fun `the button row spreads its buttons to the two ends of the row`() {
        val buttons = boxOf(screen(), "history.row.a.buttons")
        assertTrue("history: buttons are a row", buttons.horizontal)
        assertTrue("history: buttons spread", buttons.spread)
    }

    @Test
    fun `the copied notice is a surface box with an accent edge and a medium pad and its text is found inside it`() {
        val s = screen(notice = HistoryNotice.COPIED)
        val box = boxOf(s, "history.notice.box")
        assertEquals("history: notice fill", PaletteSlot.SURFACE, box.background)
        assertEquals("history: notice edge", PaletteSlot.ACCENT, box.edge)
        assertEquals("history: notice pad", Space.M, box.pad)
        assertEquals("history: notice text", HistoryTexts.NOTICE_COPIED, s.noticeLabel()?.text)
    }

    @Test
    fun `the deleted row is a horizontal surface box with the large pad and its parts spread to the two ends`() {
        val undo = boxOf(screen(pending = setOf("a")), "history.undo.a")
        assertTrue("history: undo row is a row", undo.horizontal)
        assertEquals("history: undo pad", Space.L, undo.pad)
        assertTrue("history: undo spread", undo.spread)
    }

    @Test
    fun `the empty state centres an art placeholder at the extra-large step and sets a centred display heading in caps`() {
        val s = screen(rows = emptyList())
        assertEquals("history: art placeholder size", Space.XL, spacerOf(s, EMPTY_ART_ID).size)
        val heading = labelOf(s, "history.empty")
        assertEquals("history: heading role", TypeRole.DISPLAY, heading.role)
        assertTrue("history: heading is in caps", heading.caps)
        assertEquals("history: heading is centred", TextAlign.CENTER, heading.align)
        assertEquals("history: hint is centred", TextAlign.CENTER, labelOf(s, "history.empty.hint").align)
    }

    @Test
    fun `the title block comes first and the stripes come last`() {
        val nodes = screen().nodes
        assertEquals("history: first node", "history.titlebar", nodes.first().id)
        assertEquals("history: second node", "history.rule", nodes[1].id)
        assertTrue("history: last node is the stripes", nodes.last() is BottomStripes)
        assertEquals("history: stripes id", "history.stripes", nodes.last().id)
    }

    @Test
    fun `LOAD MORE is an outlined action under a full page`() {
        val more = actionOf(screen(fullPage = true), "history.more")
        assertEquals("history: load more look", KitLook.OUTLINED, more.kit)
    }

    @Test
    fun `a carried notice goes after the notice box and never inside it`() {
        val s = screen(notice = HistoryNotice.COPIED)
        val carried = Label("carry", HistoryTexts.NOTICE_LIST_FAILED, TypeRole.BODY, PaletteSlot.DANGER)
        val out = s.withCarriedNotice(carried)
        val ids = out.nodes.map { it.id }
        assertEquals(
            "history: carried order",
            listOf(
                "history.titlebar", "history.rule", "history.gap.0", "history.notice.box", CARRIED_NOTICE_ID,
                "history.gap.1", "history.row.a", "history.stripes",
            ),
            ids,
        )
        assertEquals(
            "history: carried is right after the notice box",
            ids.indexOf("history.notice.box") + 1,
            ids.indexOf(CARRIED_NOTICE_ID),
        )
        assertTrue(
            "history: carried is inside the notice box",
            boxOf(out, "history.notice.box").children.none { it.id == CARRIED_NOTICE_ID },
        )
    }
}
