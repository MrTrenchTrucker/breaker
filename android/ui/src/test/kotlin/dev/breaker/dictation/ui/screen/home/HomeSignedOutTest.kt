package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.auth.AuthTexts
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed-out notice drawn on Home and its way to sign in. Each test renders the screen (plain JVM, node model,
 * no views) with a state that is not live drawing the notice block + `Sign in` action, or a live screen drawing neither.
 * Every assertion follows the same `.render` / node-lookup idiom as HomeScreenTest.
 */
class HomeSignedOutTest {

    private val screen = HomeScreen(TimestampFormat { it.toString() })

    /** All nodes flattened from one drawn tree by id/text. */
    private fun nodes(state: HomeState) = screen.render(state).nodes.flatMap { it.flatten() }

    @Test
    fun `the home draws the signed out notice when the session is not live`() {
        val draw = nodes(HomeState(sessionLive = false))

        // The SIGNED_OUT label carries its text, and the SIGN_IN action node carries "Sign in" with intent HomeActions.SIGN_IN.
        assertTrue("home: the SIGNED_OUT label is drawn",
            draw.filterIsInstance<Label>().any { it.id == HomeNodeIds.SIGNED_OUT && it.text == HomeTexts.SIGNED_OUT })
        val signin = draw.filterIsInstance<Action>().firstOrNull { it.id == HomeNodeIds.SIGN_IN }
        assertNotNull("home: the SIGN_IN action node is present while not live", signin)
        assertEquals("home: the action carries the Sign in label", "Sign in", signin!!.text)
        assertEquals("home: the action reports ScreenIntent.Home(HomeActions.SIGN_IN)",
            ScreenIntent.Home(HomeActions.SIGN_IN), signin.intent)
    }

    @Test
    fun `the home draws no signed out notice while the session is live`() {
        val draw = nodes(HomeState(sessionLive = true))

        // Neither the SIGNED_OUT label nor the SIGN_IN action node is drawn while live.
        assertFalse("home: the SIGNED_OUT label is not drawn while live", draw.any { it.id == HomeNodeIds.SIGNED_OUT })
        assertFalse("home: the SIGN_IN action node is not drawn while live", draw.filterIsInstance<Action>().any { it.id == HomeNodeIds.SIGN_IN })
    }

    @Test
    fun `the signed out notice says exactly the approved words`() {
        assertEquals("home: SIGNED_OUT is exactly the approved sentence", "You are signed out.", HomeTexts.SIGNED_OUT)
        assertEquals("home: SIGNED_OUT has no trailing space and no invented word", "You are signed out.".length, HomeTexts.SIGNED_OUT.length)
    }

    @Test
    fun `the way to sign in is labelled Sign in`() {
        assertEquals("home: the SIGN_IN label is \"Sign in\"", "Sign in", HomeTexts.SIGN_IN)
        assertEquals("home: SIGN_IN equals AuthTexts.SUBMIT_LOGIN reused", AuthTexts.SUBMIT_LOGIN, HomeTexts.SIGN_IN)
    }

    @Test
    fun `the sign in tap reports the sign in action to the app`() {
        val handler = HomeIntentHandler(HomeScreen(TimestampFormat { it.toString() }))

        // intentFor(SIGN_IN) is ScreenIntent.Home(HomeActions.SIGN_IN); the screen reports it, the host acts on it.
        assertEquals("home: intentFor(SIGN_IN) is ScreenIntent.Home(HomeActions.SIGN_IN)",
            ScreenIntent.Home(HomeActions.SIGN_IN), handler.intentFor(HomeNodeIds.SIGN_IN))

        // The sign-in node is not a drawer item: while the menu is open a tap on it only closes the menu
        // and changes nothing else (the action is the host's, not the screen's).
        val open = handler.tap(HomeNodeIds.MENU)
        assertTrue("home: the menu is open after the menu tap",
            open.nodes.flatMap { it.flatten() }.any { it.id == HomeNodeIds.DRAWER_PANEL })
        val closed = handler.tap(HomeNodeIds.SIGN_IN)
        assertFalse("home: the sign-in tap closes the open menu and nothing else",
            closed.nodes.flatMap { it.flatten() }.any { it.id == HomeNodeIds.DRAWER_PANEL })
    }
}
