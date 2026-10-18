package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The menu drawer as the tree draws it while the menu is open: the dim is the first drawer node and
 * takes a tap that closes the menu; the panel holds the wordmark, an accent rule under it, and the four
 * items as text rows with their words and intents. The host draws the dim and the panel as an overlay.
 */
class HomeDrawerTreeTest {
    private val screen = HomeScreen(TimestampFormat { "at $it" })
    private val open = HomeState(menuOpen = true)

    private fun top(state: HomeState): List<Node> = screen.render(state).nodes

    private inline fun <reified T : Node> nodeOf(state: HomeState, id: String): T =
        top(state).flatMap { it.flatten() }.filterIsInstance<T>().first { it.id == id }

    @Test
    fun `the dim takes a tap that closes the menu and the overlay is the dim then the panel`() {
        val dim = nodeOf<Box>(open, HomeNodeIds.DRAWER_DIM)
        assertEquals("home: the dim closes the menu on a tap", ScreenIntent.Home(HomeActions.CLOSE_MENU), dim.onClick)
        assertEquals(
            "home: the overlay is the dim and the panel, in that order",
            listOf(HomeNodeIds.DRAWER_DIM, HomeNodeIds.DRAWER_PANEL),
            top(open).map { it.id }.filter { it == HomeNodeIds.DRAWER_DIM || it == HomeNodeIds.DRAWER_PANEL },
        )
    }

    @Test
    fun `the four items are text rows with their words and their intents`() {
        val items = listOf(
            Triple(HomeNodeIds.DRAWER_HISTORY, HomeTexts.MENU_HISTORY, HomeActions.HISTORY),
            Triple(HomeNodeIds.DRAWER_SETTINGS, HomeTexts.MENU_SETTINGS, HomeActions.SETTINGS),
            Triple(HomeNodeIds.DRAWER_HOW, HomeTexts.MENU_HOW_IT_WORKS, HomeActions.HOW_IT_WORKS),
            Triple(HomeNodeIds.DRAWER_ABOUT, HomeTexts.MENU_ABOUT, HomeActions.ABOUT),
        )
        for ((id, words, action) in items) {
            val item = nodeOf<Action>(open, id)
            assertEquals("home: $id is a text row", KitLook.TEXT, item.kit)
            assertEquals("home: $id keeps its words", words, item.text)
            assertEquals("home: $id keeps its intent", ScreenIntent.Home(action), item.intent)
        }
    }

    @Test
    fun `the accent rule sits under the wordmark and above the items`() {
        val order = nodeOf<Box>(open, HomeNodeIds.DRAWER_PANEL).children.map { it.id }
        assertEquals("home: the rule is accent", PaletteSlot.ACCENT, nodeOf<Rule>(open, HomeNodeIds.DRAWER_RULE).color)
        assertEquals("home: the logo header comes first in the panel", 0, order.indexOf(HomeNodeIds.DRAWER_HEADER))
        assertEquals("home: the wordmark is the first element of the logo header", HomeNodeIds.DRAWER_WORD, nodeOf<Box>(open, HomeNodeIds.DRAWER_HEADER).children.first().id)
        assertEquals("home: the logo header is padded by L", Space.L, nodeOf<Box>(open, HomeNodeIds.DRAWER_HEADER).pad)
        assertEquals("home: the rule follows the wordmark", 1, order.indexOf(HomeNodeIds.DRAWER_RULE))
        assertTrue("home: the items follow the rule", order.indexOf(HomeNodeIds.DRAWER_HISTORY) > order.indexOf(HomeNodeIds.DRAWER_RULE))
    }

    @Test
    fun `a shut screen has no overlay nodes and the open panel keeps its surface and its words`() {
        assertTrue(
            "home: no overlay nodes while the menu is shut",
            top(HomeState()).none { it.id == HomeNodeIds.DRAWER_PANEL || it.id == HomeNodeIds.DRAWER_DIM },
        )
        assertEquals("home: the panel is the surface", PaletteSlot.SURFACE, nodeOf<Box>(open, HomeNodeIds.DRAWER_PANEL).background)
        assertEquals("home: the drawer wordmark keeps its words", HomeTexts.LOGO, nodeOf<Label>(open, HomeNodeIds.DRAWER_WORD).text)
    }
}
