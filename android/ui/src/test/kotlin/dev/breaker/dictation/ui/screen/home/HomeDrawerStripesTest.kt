package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom stripes of the home screen as nodes: the page keeps the four-bar Home look at the foot of
 * the page, and the drawer panel ends with the plain three-bar look. The colours of the bars are read
 * at the BleedMath seam, where the per-mode soft colour and the bar alternation are pinned.
 */
class HomeDrawerStripesTest {
    private val screen = HomeScreen(TimestampFormat { "at $it" })
    private val open = HomeState(menuOpen = true)

    private fun top(state: HomeState): List<Node> = screen.render(state).nodes

    private fun all(state: HomeState): List<Node> = top(state).flatMap { it.flatten() }

    private fun panel(state: HomeState): Box =
        all(state).filterIsInstance<Box>().first { it.id == HomeNodeIds.DRAWER_PANEL }

    @Test
    fun `the drawer panel ends with the plain three-bar stripes`() {
        val last = panel(open).children.last()
        assertEquals("home: the last node of the panel is the drawer stripes", HomeNodeIds.DRAWER_STRIPES, last.id)
        assertTrue("home: the drawer stripes are a bottom stripes node", last is BottomStripes)
        assertTrue("home: the drawer stripes use the plain three-bar look", (last as BottomStripes).plain)
    }

    @Test
    fun `the drawer stripes are absent while the menu is shut`() {
        assertFalse("home: no drawer stripes when the menu is shut", all(HomeState()).any { it.id == HomeNodeIds.DRAWER_STRIPES })
    }

    @Test
    fun `the page keeps the four-bar Home look as its only top-level stripes`() {
        val page = top(open).filterIsInstance<BottomStripes>()
        assertEquals("home: one page stripes node at the top level", 1, page.size)
        assertEquals("home: the page stripes are the page's node", HomeNodeIds.STRIPES, page[0].id)
        assertFalse("home: the page stripes are the four-bar Home look", page[0].plain)
    }
}
