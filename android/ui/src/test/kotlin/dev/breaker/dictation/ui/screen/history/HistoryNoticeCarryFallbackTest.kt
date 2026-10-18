package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The carried notice on a screen with neither a notice nor a title rule: it goes right after the
 * title block. HistoryNoticeCarryTest no longer covers this path, because its fixture now holds a
 * title rule, so this file covers it. The screens are built by hand, so no view, store or clock
 * is needed.
 */
class HistoryNoticeCarryFallbackTest {
    private val carried = Label("history.notice", HistoryTexts.NOTICE_LIST_FAILED, TypeRole.BODY, PaletteSlot.TEXT)

    private fun title() = Label("history.title", HistoryTexts.TITLE, TypeRole.DISPLAY, PaletteSlot.TEXT)

    private fun empty() = Label("history.empty", HistoryTexts.EMPTY, TypeRole.BODY, PaletteSlot.TEXT_MUTED)

    private fun screenOf(nodes: List<Node>) = Screen("history", HistoryTexts.TITLE, nodes)

    @Test
    fun `a carried notice with no notice and no title rule goes right after the title block`() {
        val s = screenOf(listOf(title(), empty()))
        assertEquals(
            "history: the carried notice is not right after the title block",
            listOf("history.title", CARRIED_NOTICE_ID, "history.empty"),
            s.withCarriedNotice(carried).nodes.map { it.id },
        )
    }
}
