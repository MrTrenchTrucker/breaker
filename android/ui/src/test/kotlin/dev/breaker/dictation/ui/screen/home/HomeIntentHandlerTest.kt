package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each tap on the home screen reports: the strings the host acts on, and
 * the transitions the screen owns: the drawer's open and closed, and the notice
 * a HOW IT WORKS or ABOUT tap answers with in place.
 */
class HomeIntentHandlerTest {

    private val handler = HomeIntentHandler(HomeScreen())

    private fun ids(screen: Screen): Set<String> =
        screen.nodes.flatMap { it.flatten() }.map { it.id }.toSet()

    private fun noticeIn(screen: Screen): Label? =
        screen.nodes.flatMap { it.flatten() }.filterIsInstance<Label>().firstOrNull { it.id == HomeNodeIds.NOTICE }

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

    /** A failure means a mic tap changes the drawer the screen holds, shut or open. */
    @Test
    fun `a mic tap changes no screen-local state with the drawer shut or open`() {
        val shut = handler.current()
        assertEquals("home: a mic tap with the drawer shut", shut, handler.tap("home.mic"))
        handler.tap("home.topbar.menu")
        val open = handler.current()
        assertEquals("home: a mic tap with the drawer open", open, handler.tap("home.mic"))
        assertTrue("home: the drawer is still open", "home.drawer.panel" in ids(open))
    }

    /** A failure means a mic tap clears the notice a HOW IT WORKS tap left on the screen. */
    @Test
    fun `a mic tap keeps the notice on the screen`() {
        handler.tap("home.drawer.item.how")
        val noticed = handler.current()
        assertEquals("home: a mic tap keeps the notice", noticed, handler.tap("home.mic"))
        assertEquals("home: the notice text is kept", HomeTexts.MENU_NOT_BUILT, noticeIn(noticed)?.text)
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

    /** A failure means a HOW IT WORKS or ABOUT tap does not answer in place with the notice. */
    @Test
    fun `a how-it-works or about tap answers in place with the not-built line`() {
        val how = handler.tap("home.drawer.item.how")
        val howNotice = noticeIn(how)
        assertTrue("home: a HOW IT WORKS tap draws the notice", howNotice != null)
        assertEquals(HomeTexts.MENU_NOT_BUILT, howNotice?.text)
        val about = handler.tap("home.drawer.item.about")
        val aboutNotice = noticeIn(about)
        assertTrue("home: an ABOUT tap draws the notice", aboutNotice != null)
        assertEquals(HomeTexts.MENU_NOT_BUILT, aboutNotice?.text)
        assertTrue("home: the drawer is closed after the answer", "home.drawer.panel" !in ids(about))
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
}
