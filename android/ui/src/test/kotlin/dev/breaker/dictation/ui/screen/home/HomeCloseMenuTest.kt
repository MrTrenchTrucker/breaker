package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The close intent the host sends from its Back callback: on an open menu it shuts the drawer and
 * changes nothing else, and on a shut menu it leaves the screen as it was.
 */
class HomeCloseMenuTest {

    private val handler = HomeIntentHandler(HomeScreen())

    private fun ids(screen: Screen): Set<String> =
        screen.nodes.flatMap { it.flatten() }.map { it.id }.toSet()

    /** A failure means the close intent leaves the drawer open. */
    @Test
    fun `the close intent shuts an open menu`() {
        handler.show(HomeState(menuOpen = true))
        val closed = handler.handle(ScreenIntent.Home(HomeActions.CLOSE_MENU))
        assertFalse("home: the close intent must shut the open menu", HomeNodeIds.DRAWER_PANEL in ids(closed))
    }

    /** A failure means the close intent changes a screen whose menu was already shut. */
    @Test
    fun `the close intent on a shut menu changes nothing`() {
        val before = ids(handler.show(HomeState(menuOpen = false)))
        val after = ids(handler.handle(ScreenIntent.Home(HomeActions.CLOSE_MENU)))
        assertEquals("home: a shut menu must stay as it was", before, after)
    }
}
