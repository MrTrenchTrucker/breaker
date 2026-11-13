package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed-out notice drawn on Home and its way to sign in.
 *
 * This slice moves the block to Settings. Home no longer draws it: a state that
 * is not live draws neither the SIGNED_OUT label nor the SIGN_IN action node,
 * and neither old node id nor old text appears anywhere in the tree.
 */
class HomeSignedOutTest {

    private val screen = HomeScreen(TimestampFormat { it.toString() })

    /** All nodes flattened from one drawn tree by id/text. */
    private fun nodes(state: HomeState) = screen.render(state).nodes.flatMap { it.flatten() }

    @Test
    fun `home no longer shows the signed out block when the session is not live`() {
        val draw = nodes(HomeState(sessionLive = false))

        // Neither the old SIGNED_OUT label nor the old SIGN_IN action node is drawn,
        // and the old texts appear nowhere in the tree.
        assertFalse("home: no SIGNED_OUT label while not live", draw.any { it.id == "home.signedout.notice" })
        assertFalse("home: no SIGN_IN action node while not live",
            draw.filterIsInstance<Action>().any { it.id == "home.signedout.signin" })
        assertTrue("home: the signed-out notice text is not drawn",
            draw.filterIsInstance<Label>().none { it.text == "You are signed out." })
        assertTrue("home: the sign-in action text is not drawn",
            draw.filterIsInstance<Action>().none { it.text == "Sign in" })
    }

    @Test
    fun `home draws no signed out notice while the session is live`() {
        val draw = nodes(HomeState(sessionLive = true))

        // Neither the SIGNED_OUT label nor the SIGN_IN action node is drawn while live.
        assertFalse("home: the SIGNED_OUT label is not drawn while live", draw.any { it.id == "home.signedout.notice" })
        assertFalse("home: the SIGN_IN action node is not drawn while live",
            draw.filterIsInstance<Action>().any { it.id == "home.signedout.signin" })
    }
}
