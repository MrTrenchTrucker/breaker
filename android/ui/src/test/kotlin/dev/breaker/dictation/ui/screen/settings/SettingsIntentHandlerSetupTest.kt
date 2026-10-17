package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settings screen offers nothing for the set-up walk-through, so a set-up intent that
 * reaches the settings handler is refused: a message says so, the store is not read or
 * written, and the rows stay as they were.
 */
class SettingsIntentHandlerSetupTest {
    private val store = FakeSettingsStore(NONDEFAULT)
    private val handler = SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())

    private fun noticeText(result: Rendered): String? =
        result.screen.nodes.flatMap { it.flatten() }.filterIsInstance<Label>().firstOrNull { it.id == "settings.notice" }?.text

    @Test
    fun `a set-up intent is not accepted and leaves the store and the rows alone`() {
        val before = handler.current()
        val loadsBefore = store.loadAttempts
        val savesBefore = store.saveAttempts
        for (action in listOf("recheck", "switch.on", "open.overlay", "")) {
            val after = handler.handle(ScreenIntent.Setup(action))
            assertEquals("set-up action '$action'", Notice.NOT_ACCEPTED.text, noticeText(after))
            assertEquals("the store was read for '$action'", loadsBefore, store.loadAttempts)
            assertEquals("the store was written for '$action'", savesBefore, store.saveAttempts)
            assertEquals("the rows changed for '$action'", before.screen.nodes.filterNot { it.id == "settings.notice" }, after.screen.nodes.filterNot { it.id == "settings.notice" })
            assertEquals(NONDEFAULT, store.current)
        }
    }

    @Test
    fun `the settings screen offers no edit for a set-up intent`() {
        for (action in listOf("recheck", "switch.on", "switch.off", "open.overlay", "")) {
            assertNull("set-up action '$action'", SettingsScreen().editFor(ScreenIntent.Setup(action)))
        }
    }

    @Test
    fun `the refusal goes with the next action that works`() {
        handler.handle(ScreenIntent.Setup("recheck"))
        val after = handler.handle(ScreenIntent.SetSetting("preloadModel", "true"))
        assertNull(noticeText(after))
        assertEquals(NONDEFAULT.copy(preloadModel = true), store.current)
    }
}
