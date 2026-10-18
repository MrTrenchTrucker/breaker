package dev.breaker.dictation.ui.screen.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one collapse decision a long card reads: how many drawn lines it may show and which
 * middle button, if any, it offers. The cap is pinned once here, and the three outcomes are
 * pinned at the boundary and far from it, closed and open.
 */
class HistoryCollapseTest {
    @Test
    fun `the cap is six drawn lines and is pinned here once`() {
        assertEquals("history: the cap", 6, COLLAPSE_LINES)
    }

    @Test
    fun `five drawn lines show whole with no middle button, closed or open`() {
        assertEquals("history: five closed", Collapse(null, Middle.NONE), collapse(5, false))
        assertEquals("history: five open", Collapse(null, Middle.NONE), collapse(5, true))
    }

    @Test
    fun `six drawn lines show whole with no middle button, closed or open`() {
        assertEquals("history: six closed", Collapse(null, Middle.NONE), collapse(6, false))
        assertEquals("history: six open", Collapse(null, Middle.NONE), collapse(6, true))
    }

    @Test
    fun `seven drawn lines close to the cap and offer Show all`() {
        val result = collapse(7, false)
        assertEquals("history: seven closed", Collapse(COLLAPSE_LINES, Middle.SHOW_ALL), result)
        assertEquals("history: the cut is the cap", COLLAPSE_LINES, result.maxLines)
    }

    @Test
    fun `seven drawn lines open show the whole text and offer Hide`() {
        val result = collapse(7, true)
        assertEquals("history: seven open", Collapse(null, Middle.HIDE), result)
        assertEquals("history: an open card has no cut", null, result.maxLines)
    }

    @Test
    fun `a long card keeps the same rule at any length`() {
        assertEquals("history: forty closed", Collapse(COLLAPSE_LINES, Middle.SHOW_ALL), collapse(40, false))
        assertEquals("history: forty open", Collapse(null, Middle.HIDE), collapse(40, true))
    }

    @Test
    fun `no drawn lines gives no middle button`() {
        assertEquals("history: no lines closed", Collapse(null, Middle.NONE), collapse(0, false))
    }

    @Test
    fun `the middle values are none, Show all and Hide in that order`() {
        assertEquals("history: middle values", listOf("NONE", "SHOW_ALL", "HIDE"), Middle.entries.map { it.name })
    }
}
