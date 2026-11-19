package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.ScreenIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The set-up intent carries an optional id. No id and an empty id are the same intent, a named id is another
 * intent, and the model actions are prefixed so that no set-up action can equal one of them.
 */
class SetupActionIdTest {
    @Test
    fun `a set up intent with no id equals one with an empty id`() {
        assertEquals(ScreenIntent.Setup("x"), ScreenIntent.Setup("x", ""))
    }

    @Test
    fun `a set up intent with a named id differs from one with no id`() {
        assertNotEquals(ScreenIntent.Setup("x"), ScreenIntent.Setup("x", "a"))
    }

    @Test
    fun `the model actions are the prefixed strings and equal no set up action`() {
        val model = listOf(ModelActions.MODEL_DOWNLOAD, ModelActions.MODEL_DELETE, ModelActions.MODEL_USE)
        assertEquals(listOf("model-download", "model-delete", "model-use"), model)
        val setupActions = listOf(
            SetupActions.DOWNLOAD_MODEL,
            SetupActions.SWITCH_ON,
            SetupActions.SWITCH_OFF,
            SetupActions.RECHECK,
        ) + SetupActions.OPENING
        assertEquals("a model action equals a set up action", emptyList<String>(), model.filter { it in setupActions })
    }
}
