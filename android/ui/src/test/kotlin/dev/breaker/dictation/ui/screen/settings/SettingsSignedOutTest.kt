package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings screen draws when the session is not live: the signed-out
 * notice and the Sign in action, placed after the values block and before the
 * stripes, even when settings have never been read.
 *
 * The host (SettingsHostView) hands sessionLive() through every render; this test
 * pins the screen-level behaviour: signedOut = true draws the block,
 * signedOut = false draws nothing of it.
 */
class SettingsSignedOutTest {

    private val screen = SettingsScreen()

    private fun themeOf(
        stored: StoredThemeMode = StoredThemeMode.LIGHT,
        shown: dev.breaker.shared.tokens.ThemeMode = dev.breaker.shared.tokens.ThemeMode.LIGHT,
        loaded: Boolean = true,
    ) = ThemeState(stored, shown, loaded)

    /** Every node of [s], parents before children. */
    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun nodeId(s: Screen, id: String): String? = nodesOf(s).firstOrNull { it.id == id }?.id

    private fun labelText(s: Screen, id: String): String? = (nodesOf(s).firstOrNull { it.id == id } as? Label)?.text

    private fun actionText(s: Screen, id: String): String? = (nodesOf(s).firstOrNull { it.id == id } as? Action)?.text

    private fun actionIntent(s: Screen, id: String): ScreenIntent? =
        (nodesOf(s).firstOrNull { it.id == id } as? Action)?.intent

    @Test
    fun `signed out false draws no signed-out block`() {
        val s = screen.render(NONDEFAULT, themeOf(), notice = null, signedOut = false)
        val every = nodesOf(s)
        assertFalse("no SIGNED_OUT label id: $every", every.any { it.id == "settings.signedout.notice" })
        assertFalse("no SIGN_IN action id: $every",
            every.filterIsInstance<Action>().any { it.id == "settings.signedout.signin" })
        assertTrue("no You are signed out. text: $every",
            every.filterIsInstance<Label>().none { it.text == "You are signed out." })
        assertTrue("no Sign in text: $every",
            every.filterIsInstance<Action>().none { it.text == "Sign in" })
    }

    @Test
    fun `signed out true draws the block after the values block and before the stripes`() {
        val s = screen.render(NONDEFAULT, themeOf(), notice = null, signedOut = true)
        val every = nodesOf(s)
        val label = every.filterIsInstance<Label>().firstOrNull { it.id == "settings.signedout.notice" }
        val action = every.filterIsInstance<Action>().firstOrNull { it.id == "settings.signedout.signin" }
        assertNotNull("SIGNED_OUT label drawn", label)
        assertNotNull("SIGN_IN action drawn", action)
        assertEquals("You are signed out.", label!!.text)
        assertEquals("Sign in", action!!.text)
        assertEquals(ScreenIntent.Home(dev.breaker.dictation.ui.screen.home.HomeActions.SIGN_IN), action!!.intent)

        // Order: values block (settings.server.field is the last value row), then signed-out block, then stripes.
        val positions = every.mapIndexed { i, n -> n.id to i }.toMap()
        assertTrue("values block before signed-out: ${positions["settings.server.field"]} < ${positions["settings.signedout.notice"]}",
            positions["settings.server.field"]!! < positions["settings.signedout.notice"]!!)
        assertTrue("signed-out before stripes: ${positions["settings.signedout.notice"]} < ${positions["settings.stripe"]}",
            positions["settings.signedout.notice"]!! < positions["settings.stripe"]!!)
    }

    @Test
    fun `signed out true draws the block even when settings are null`() {
        val s = screen.render(null, themeOf(), notice = null, signedOut = true)
        val every = nodesOf(s)
        assertFalse("no value rows when settings null",
            every.any { it.id == "settings.server.field" || it.id == "settings.model.value" })
        assertTrue("SIGNED_OUT label drawn with null settings",
            every.any { it.id == "settings.signedout.notice" })
        assertTrue("SIGN_IN action drawn with null settings",
            every.any { it.id == "settings.signedout.signin" })
    }
}
