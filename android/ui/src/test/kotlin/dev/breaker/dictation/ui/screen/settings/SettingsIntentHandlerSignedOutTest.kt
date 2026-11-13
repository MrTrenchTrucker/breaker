package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings handler and the signed-out flag: what the host sets with showingSignedOut is what the next
 * screen draws, and an intent handled afterwards keeps it. Without this, the handler could ignore the flag
 * or drop the setter and every screen-level test would still pass.
 */
class SettingsIntentHandlerSignedOutTest {
    private val noticeId = "settings.signedout.notice"
    private val signInId = "settings.signedout.signin"

    private fun newHandler(): SettingsIntentHandler {
        val store = FakeSettingsStore(NONDEFAULT)
        return SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())
    }

    private fun Rendered.ids(): List<String> = screen.nodes.flatMap { it.flatten() }.map(Node::id)

    private fun Rendered.drawsBlock(): Boolean = noticeId in ids() && signInId in ids()

    private fun Rendered.drawsNoPartOfBlock(): Boolean = noticeId !in ids() && signInId !in ids()

    @Test
    fun `a handler that was never told it is signed out draws no block`() {
        assertTrue("screen: settings handler: the block shows before the host set anything", newHandler().current().drawsNoPartOfBlock())
    }

    @Test
    fun `showing signed out true makes the next screen draw the notice and the sign in action`() {
        val handler = newHandler()
        handler.showingSignedOut(true)
        val ids = handler.current().ids()
        assertTrue("screen: settings handler: no notice after showingSignedOut(true): $ids", noticeId in ids)
        assertTrue("screen: settings handler: no sign in action after showingSignedOut(true): $ids", signInId in ids)
    }

    @Test
    fun `showing signed out false takes the block away again`() {
        val handler = newHandler()
        handler.showingSignedOut(true)
        assertTrue("screen: settings handler: control: the block must be there first", handler.current().drawsBlock())
        handler.showingSignedOut(false)
        assertTrue("screen: settings handler: the block stayed after showingSignedOut(false)", handler.current().drawsNoPartOfBlock())
    }

    @Test
    fun `the flag survives an intent that is handled afterwards`() {
        val handler = newHandler()
        handler.showingSignedOut(true)
        val afterToggle = handler.handle(ScreenIntent.ToggleTheme)
        assertTrue("screen: settings handler: the block was lost after a theme toggle: ${afterToggle.ids()}", afterToggle.drawsBlock())
        val afterRefused = handler.handle(ScreenIntent.SetSetting("no.such.setting", "x"))
        assertTrue("screen: settings handler: the block was lost after a refused setting: ${afterRefused.ids()}", afterRefused.drawsBlock())
    }

    @Test
    fun `a signed in flag stays signed in through a handled intent`() {
        val handler = newHandler()
        handler.showingSignedOut(false)
        assertFalse("screen: settings handler: a handled intent drew the block while signed in", handler.handle(ScreenIntent.ToggleTheme).drawsBlock())
    }
}
