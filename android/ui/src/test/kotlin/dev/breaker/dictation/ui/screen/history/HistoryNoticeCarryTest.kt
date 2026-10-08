package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The helpers that place a carried notice in a screen. The screens are built by hand, so
 * no view, store or clock is needed.
 */
class HistoryNoticeCarryTest {
    private val carried = Label("history.notice", HistoryTexts.NOTICE_LIST_FAILED, TypeRole.BODY, PaletteSlot.TEXT)

    private fun title() = Label("history.title", HistoryTexts.TITLE, TypeRole.DISPLAY, PaletteSlot.TEXT)

    private fun notice(text: String) = Label("history.notice", text, TypeRole.BODY, PaletteSlot.TEXT)

    private fun empty() = Label("history.empty", HistoryTexts.EMPTY, TypeRole.BODY, PaletteSlot.TEXT_MUTED)

    private fun screenOf(nodes: List<Node>) = Screen("history", HistoryTexts.TITLE, nodes)

    private fun idsOf(s: Screen): List<String> = s.nodes.map { it.id }

    @Test
    fun `a carried notice goes right after the notice node`() {
        val s = screenOf(listOf(title(), notice(HistoryTexts.NOTICE_COPIED), empty()))
        val out = s.withCarriedNotice(carried)
        assertEquals(
            "history: the carried notice is not right after the notice node",
            listOf("history.title", "history.notice", CARRIED_NOTICE_ID, "history.empty"),
            idsOf(out),
        )
        val added = out.nodes[2] as Label
        assertEquals("history: the carried notice text changed", HistoryTexts.NOTICE_LIST_FAILED, added.text)
        assertEquals("history: the carried notice role changed", TypeRole.BODY, added.role)
        assertEquals("history: the carried notice colour changed", PaletteSlot.TEXT, added.color)
    }

    @Test
    fun `a carried notice goes right after the title when there is no notice`() {
        val s = screenOf(listOf(title(), empty()))
        assertEquals(
            "history: the carried notice is not right after the title",
            listOf("history.title", CARRIED_NOTICE_ID, "history.empty"),
            idsOf(s.withCarriedNotice(carried)),
        )
    }

    @Test
    fun `a carried notice with the same text as the notice is not added`() {
        val s = screenOf(listOf(title(), notice(HistoryTexts.NOTICE_LIST_FAILED)))
        assertSame("history: a carried notice with the same text as the notice was added", s, s.withCarriedNotice(carried))
    }

    @Test
    fun `noticeLabel finds the notice label and returns null without one`() {
        assertEquals(
            "history: noticeLabel did not find the notice label",
            HistoryTexts.NOTICE_COPIED,
            screenOf(listOf(title(), notice(HistoryTexts.NOTICE_COPIED))).noticeLabel()?.text,
        )
        assertNull("history: noticeLabel found a label without a notice", screenOf(listOf(title(), empty())).noticeLabel())
    }
}
