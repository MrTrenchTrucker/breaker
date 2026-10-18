package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.home.HomeScreen
import dev.breaker.dictation.ui.screen.home.HomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The History entry rows draw their copy, toggle and delete buttons at the compact width, and no
 * other button on the History or the Home screen does. The screens are read as their node trees,
 * so a flag set on the wrong node is reported by its id.
 */
class HistoryCompactButtonsTest {
    private val row = HistoryRow("a", "TIME-a", TranscriptionSource.LOCAL, "words a")

    private fun history(
        pending: Set<String> = emptySet(),
        opened: Set<String> = emptySet(),
        fullPage: Boolean = false,
    ): Screen = HistoryScreen().render(HistoryState(listOf(row), pending, PAGE_SIZE, fullPage, opened = opened), null)

    private fun actionOf(screen: Screen, id: String): Action =
        screen.node(id) as? Action ?: throw AssertionError("no action $id on the screen")

    private val rowButtons = listOf("history.row.a.copy", "history.row.a.toggle", "history.row.a.delete")

    @Test
    fun `the copy toggle and delete buttons of a closed History row are compact`() {
        val screen = history()
        for (id in rowButtons) {
            assertTrue("history: $id is compact", actionOf(screen, id).compact)
        }
    }

    @Test
    fun `the three buttons of an opened History row are compact too`() {
        val screen = history(opened = setOf("a"))
        assertEquals("history: the row is open", HistoryTexts.BUTTON_HIDE, actionOf(screen, "history.row.a.toggle").text)
        for (id in rowButtons) {
            assertTrue("history: $id is compact when the row is open", actionOf(screen, id).compact)
        }
    }

    @Test
    fun `LOAD MORE is not compact`() {
        val more = actionOf(history(fullPage = true), "history.more")
        assertFalse("history: LOAD MORE keeps the default width", more.compact)
    }

    @Test
    fun `the Undo button of a deleted row keeps the default width`() {
        val undo = actionOf(history(pending = setOf("a")), "history.undo.a.button")
        assertFalse("history: Undo keeps the default width", undo.compact)
    }

    @Test
    fun `the Home card buttons keep the default width`() {
        val state = HomeState(
            switchOn = true,
            modelReady = true,
            lastDictationAt = 1L,
            lastTranscript = "Running about twenty minutes late,",
            transcriptTime = "06:42",
        )
        val home = HomeScreen(TimestampFormat { "at $it" }).render(state)
        for (id in listOf("home.transcript.copy", "home.transcript.toggle")) {
            assertFalse("home: $id keeps the default width", actionOf(home, id).compact)
        }
    }

    @Test
    fun `a plain action is not compact unless it is told to be`() {
        val plain = Action(id = "plain", text = "go", intent = ScreenIntent.ToggleTheme)
        assertFalse("an action is not compact by default", plain.compact)
        val told = Action(id = "told", text = "go", intent = ScreenIntent.ToggleTheme, compact = true)
        assertTrue("an action that is told is compact", told.compact)
    }
}
