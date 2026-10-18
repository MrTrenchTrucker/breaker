package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Switch
import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each tap on the home screen reports: the strings the host acts on, and
 * the transitions the screen owns: the drawer's open and closed, and the
 * close-only rule that a drawer item or a dim tap closes it while any other
 * tap with the menu open also closes it and does nothing else.
 */
class HomeIntentHandlerTest {

    private val handler = HomeIntentHandler(HomeScreen())

    private fun ids(screen: Screen): Set<String> =
        screen.nodes.flatMap { it.flatten() }.map { it.id }.toSet()

    /** A failure means a tap reports the wrong string, or one the host cannot act on. */
    @Test
    fun `each tap reports its HomeActions string`() {
        assertEquals(ScreenIntent.Home(HomeActions.MENU), handler.intentFor("home.topbar.menu"))
        assertEquals(ScreenIntent.Home(HomeActions.SWITCH), handler.intentFor("home.card.switch"))
        assertEquals(ScreenIntent.Home(HomeActions.COPY), handler.intentFor("home.transcript.copy"))
        assertEquals(ScreenIntent.Home(HomeActions.HISTORY), handler.intentFor("home.drawer.item.history"))
        assertEquals(ScreenIntent.Home(HomeActions.SETTINGS), handler.intentFor("home.drawer.item.settings"))
        assertEquals(ScreenIntent.Home(HomeActions.HOW_IT_WORKS), handler.intentFor("home.drawer.item.how"))
        assertEquals(ScreenIntent.Home(HomeActions.ABOUT), handler.intentFor("home.drawer.item.about"))
    }

    /** A failure means a tap on something that is not a control reports an intent. */
    @Test
    fun `a tap on a node that is not a control reports nothing`() {
        assertNull(handler.intentFor("home.mic.hint"))
        assertNull(handler.intentFor("home.mic.word"))
        assertNull(handler.intentFor("home.background"))
        assertNull(handler.intentFor("home.card.word"))
        assertNull(handler.intentFor("home.card.row.model.key"))
        assertNull(handler.intentFor("home.stripe.1"))
    }

    /** A failure means a tap on the big mic does not report the MIC string the host acts on. */
    @Test
    fun `the big mic tap reports the MIC string`() {
        assertEquals(ScreenIntent.Home(HomeActions.MIC), handler.intentFor("home.mic"))
    }

    /** A failure means a mic tap changes the drawer when shut, or does not close it when open. */
    @Test
    fun `a mic tap changes no screen-local state with the drawer shut and closes it with the drawer open`() {
        val shut = handler.current()
        assertEquals("home: a mic tap with the drawer shut", shut, handler.tap("home.mic"))
        handler.tap("home.topbar.menu")
        val closed = handler.tap("home.mic")
        assertFalse("home: the drawer is closed after a mic tap", "home.drawer.panel" in ids(closed))
    }

    /** A failure means the menu does not open the drawer, or a drawer item does not close it. */
    @Test
    fun `the menu tap opens the drawer and a drawer item closes it`() {
        val opened = handler.tap("home.topbar.menu")
        assertTrue("home: the drawer is open", "home.drawer.panel" in ids(opened))
        val closed = handler.tap("home.drawer.item.history")
        assertTrue("home: the drawer is closed", "home.drawer.panel" !in ids(closed))
        val closedToo = handler.tap("home.drawer.item.about")
        assertTrue("home: the drawer stays closed", "home.drawer.panel" !in ids(closedToo))
    }

    /** A failure means a second tap on the menu does not close the drawer again. */
    @Test
    fun `a second menu tap closes the drawer`() {
        val opened = handler.tap("home.topbar.menu")
        assertTrue("home: the first menu tap opens the drawer", "home.drawer.panel" in ids(opened))
        val closed = handler.tap("home.topbar.menu")
        assertTrue("home: the second menu tap closes the drawer", "home.drawer.panel" !in ids(closed))
    }

    /** Item 1: a HISTORY tap closes the menu and draws no notice. */
    @Test
    fun `a HISTORY tap closes the menu with no notice`() {
        handler.tap("home.topbar.menu")
        val screen = handler.tap("home.drawer.item.history")
        assertNull("home: no notice after HISTORY", handler.notice())
        assertFalse("home: no notice node after HISTORY", ids(screen).contains(HomeNodeIds.NOTICE))
        assertFalse("home: the menu is closed after HISTORY", ids(screen).contains("home.drawer.panel"))
    }

    /** Item 1: a SETTINGS tap closes the menu and draws no notice. */
    @Test
    fun `a SETTINGS tap closes the menu with no notice`() {
        handler.tap("home.topbar.menu")
        val screen = handler.tap("home.drawer.item.settings")
        assertNull("home: no notice after SETTINGS", handler.notice())
        assertFalse("home: no notice node after SETTINGS", ids(screen).contains(HomeNodeIds.NOTICE))
        assertFalse("home: the menu is closed after SETTINGS", ids(screen).contains("home.drawer.panel"))
    }

    /** Item 1: a HOW_IT_WORKS tap closes the menu and draws no notice. */
    @Test
    fun `a HOW_IT_WORKS tap closes the menu with no notice`() {
        handler.tap("home.topbar.menu")
        val screen = handler.tap("home.drawer.item.how")
        assertNull("home: no notice after HOW_IT_WORKS", handler.notice())
        assertFalse("home: no notice node after HOW_IT_WORKS", ids(screen).contains(HomeNodeIds.NOTICE))
        assertFalse("home: the menu is closed after HOW_IT_WORKS", ids(screen).contains("home.drawer.panel"))
    }

    /** Item 1: an ABOUT tap closes the menu and draws no notice. */
    @Test
    fun `an ABOUT tap closes the menu with no notice`() {
        handler.tap("home.topbar.menu")
        val screen = handler.tap("home.drawer.item.about")
        assertNull("home: no notice after ABOUT", handler.notice())
        assertFalse("home: no notice node after ABOUT", ids(screen).contains(HomeNodeIds.NOTICE))
        assertFalse("home: the menu is closed after ABOUT", ids(screen).contains("home.drawer.panel"))
    }

    /** A failure means an intent this screen does not own changes the drawer. */
    @Test
    fun `an intent this screen does not own is drawn from the state held`() {
        handler.tap("home.topbar.menu")
        val drawn = handler.handle(ScreenIntent.ToggleTheme)
        assertTrue("home: the drawer is still open", "home.drawer.panel" in ids(drawn))
        val history = handler.handle(ScreenIntent.History("COPY", "row-1"))
        assertTrue("home: the drawer is still open", "home.drawer.panel" in ids(history))
    }

    /** Item 5: while the menu is open, a mic tap closes it and changes nothing else. */
    @Test
    fun `a mic tap with the menu open closes it and changes nothing else`() {
        handler.tap("home.topbar.menu")
        val after = handler.tap("home.mic")
        assertFalse("home: the menu is closed", ids(after).contains("home.drawer.panel"))
        assertNull("home: no notice", handler.notice())
        assertFalse("home: no notice node", ids(after).contains(HomeNodeIds.NOTICE))
    }

    /** Item 5: while the menu is open, a switch tap closes it and does not toggle the switch. */
    @Test
    fun `a switch tap with the menu open closes it and changes nothing else`() {
        handler.tap("home.topbar.menu")
        val after = handler.tap("home.card.switch")
        assertFalse("home: the menu is closed", ids(after).contains("home.drawer.panel"))
        val switchNode = after.nodes.flatMap { it.flatten() }.filterIsInstance<Switch>().first { it.id == HomeNodeIds.SWITCH }
        assertFalse("home: the switch did not toggle", switchNode.on)
    }

    /** Item 5: while the menu is open, a copy tap closes it and changes nothing else. */
    @Test
    fun `a copy tap with the menu open closes it and changes nothing else`() {
        handler.tap("home.topbar.menu")
        val after = handler.tap("home.transcript.copy")
        assertFalse("home: the menu is closed", ids(after).contains("home.drawer.panel"))
        assertNull("home: no notice", handler.notice())
    }

    /** Item 5: while the menu is open, a toggle tap closes it and does not open the transcript. */
    @Test
    fun `a toggle tap with the menu open closes it and changes nothing else`() {
        handler.tap("home.topbar.menu")
        val after = handler.tap("home.transcript.toggle")
        assertFalse("home: the menu is closed", ids(after).contains("home.drawer.panel"))
        val toggle = after.nodes.flatMap { it.flatten() }.filterIsInstance<Action>().first { it.id == HomeNodeIds.TOGGLE }
        assertEquals("home: the toggle still reads Show all", HomeTexts.BUTTON_SHOW_ALL, toggle.text)
    }

    /** Item 5: a tap on the drawer dim closes the menu. */
    @Test
    fun `a tap on the drawer dim closes the menu`() {
        handler.tap("home.topbar.menu")
        val after = handler.tap("home.drawer.dim")
        assertFalse("home: the menu is closed", ids(after).contains("home.drawer.panel"))
    }

    /** Item 5: with the menu closed, each action acts normally. */
    @Test
    fun `with the menu closed each action acts`() {
        val afterMic = handler.tap("home.mic")
        assertEquals("home: mic with menu closed changes nothing", handler.current(), afterMic)
        val afterSwitch = handler.tap("home.card.switch")
        assertEquals("home: switch with menu closed changes nothing", handler.current(), afterSwitch)
        val afterCopy = handler.tap("home.transcript.copy")
        assertEquals("home: copy with menu closed changes nothing", handler.current(), afterCopy)
        val afterToggle = handler.tap("home.transcript.toggle")
        val toggle = afterToggle.nodes.flatMap { it.flatten() }.filterIsInstance<Action>().first { it.id == HomeNodeIds.TOGGLE }
        assertEquals("home: the toggle reads Hide after opening", HomeTexts.BUTTON_HIDE, toggle.text)
        val afterToggle2 = handler.tap("home.transcript.toggle")
        val toggle2 = afterToggle2.nodes.flatMap { it.flatten() }.filterIsInstance<Action>().first { it.id == HomeNodeIds.TOGGLE }
        assertEquals("home: the toggle reads Show all after closing", HomeTexts.BUTTON_SHOW_ALL, toggle2.text)
    }
}
