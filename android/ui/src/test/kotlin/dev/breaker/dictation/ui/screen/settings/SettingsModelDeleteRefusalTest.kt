package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A model delete that reaches the settings handler as a set-up intent is refused with the not-accepted
 * message, for the model in use and for any other model. The store is not read or written and the rows
 * stay as they were. The host answers a delete of the model in use before the handler is reached.
 */
class SettingsModelDeleteRefusalTest {
    private val store = FakeSettingsStore(NONDEFAULT)
    private val handler = SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())

    private fun noticeText(result: Rendered): String? =
        result.screen.nodes.flatMap { it.flatten() }.filterIsInstance<Label>().firstOrNull { it.id == "settings.notice" }?.text

    @Test
    fun `a model delete intent reaching the settings handler is not accepted`() {
        val before = handler.current()
        val loadsBefore = store.loadAttempts
        val savesBefore = store.saveAttempts
        for (id in listOf(NONDEFAULT.modelSize, "parakeet")) {
            val after = handler.handle(ScreenIntent.Setup(ModelActions.MODEL_DELETE, id))
            assertEquals("model delete of '$id'", Notice.NOT_ACCEPTED.text, noticeText(after))
            assertEquals("the store was read for the delete of '$id'", loadsBefore, store.loadAttempts)
            assertEquals("the store was written for the delete of '$id'", savesBefore, store.saveAttempts)
            assertEquals(
                "the rows changed for the delete of '$id'",
                before.screen.nodes.filterNot { it.id == "settings.notice" },
                after.screen.nodes.filterNot { it.id == "settings.notice" },
            )
            assertEquals(NONDEFAULT, store.current)
        }
    }

    @Test
    fun `the settings screen offers no edit for a model delete intent`() {
        assertNull(SettingsScreen().editFor(ScreenIntent.Setup(ModelActions.MODEL_DELETE, "parakeet")))
    }
}
