package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.HistoryRow
import dev.breaker.dictation.ui.screen.history.HistoryScreen
import dev.breaker.dictation.ui.screen.history.HistoryState
import dev.breaker.dictation.ui.screen.history.PAGE_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The Home transcript box with an empty transcript and with a full one, and the History rows
 * built the way HistoryScreen builds them. An empty box keeps its place and its empty word and
 * has no Copy action; a full box and a History row keep their Copy action with its intent.
 */
class HomeCopyEmptyBoxTest {

    private val screen = HomeScreen()

    private val empty = HomeState()

    private val blank = HomeState(lastTranscript = "")

    private val full = HomeState(lastTranscript = "Running about twenty minutes late,")

    private fun tree(state: HomeState): List<Node> = screen.render(state).nodes.flatMap { it.flatten() }

    private fun homeCopyOf(state: HomeState): Action? =
        tree(state).filterIsInstance<Action>().firstOrNull { it.id == "home.transcript.copy" }

    private fun homeTextOf(state: HomeState): CollapsibleText =
        tree(state).filterIsInstance<CollapsibleText>().first { it.id == "home.transcript.text" }

    private fun historyCopyOf(row: HistoryRow): Action? =
        HistoryScreen().render(HistoryState(listOf(row), emptySet(), PAGE_SIZE, false), null)
            .nodes.flatMap { it.flatten() }
            .filterIsInstance<Action>()
            .firstOrNull { it.id == "history.row.${row.id}.copy" }

    /** A failure means an empty box still has a Copy action, or it no longer draws the empty word. */
    @Test
    fun `an empty transcript box has no copy action and still draws the empty word`() {
        assertNull("home: no copy action when the transcript is null", homeCopyOf(empty))
        assertNull("home: no copy action when the transcript is blank", homeCopyOf(blank))
        assertEquals("home: the empty word when the transcript is null", HomeTexts.EMPTY_BOX, homeTextOf(empty).text)
        assertEquals("home: the empty word when the transcript is blank", HomeTexts.EMPTY_BOX, homeTextOf(blank).text)
    }

    /** A failure means a whitespace-only transcript box has a Copy action, or no longer draws the empty word. */
    @Test
    fun `a whitespace-only transcript box has no copy action and still draws the empty word`() {
        val spaces = HomeState(lastTranscript = "  \n \t")
        assertNull("home: no copy action when the transcript is only whitespace", homeCopyOf(spaces))
        assertEquals("home: the empty word when the transcript is only whitespace", HomeTexts.EMPTY_BOX, homeTextOf(spaces).text)
    }

    /** A failure means a transcript with text and surrounding whitespace has lost its copy action, or its copy intent. */
    @Test
    fun `a transcript with text and surrounding whitespace keeps its copy action`() {
        val padded = HomeState(lastTranscript = "  hello \n")
        val copy = homeCopyOf(padded) ?: throw AssertionError("home: a padded box has no copy action")
        assertTrue("home: the padded copy is enabled", copy.enabled)
        assertEquals("home: the padded copy intent", ScreenIntent.Home(HomeActions.COPY), copy.intent)
    }

    /** A failure means a full transcript box has no copy action, or its copy action reports another tap. */
    @Test
    fun `a full transcript box has an enabled copy action with the copy intent`() {
        val copy = homeCopyOf(full) ?: throw AssertionError("home: a full box has no copy action")
        assertTrue("home: the copy is enabled", copy.enabled)
        assertEquals("home: the copy intent", ScreenIntent.Home(HomeActions.COPY), copy.intent)
    }

    /** A failure means a History row with text has lost its Copy action, or its copy intent. */
    @Test
    fun `a History row keeps its copy action with the copy intent`() {
        val row = HistoryRow("a", "TIME-a", TranscriptionSource.LOCAL, "words a")
        val copy = historyCopyOf(row) ?: throw AssertionError("history: a row has no copy action")
        assertTrue("history: the row copy is enabled", copy.enabled)
        assertEquals("history: the row copy intent", ScreenIntent.History(HistoryActions.COPY, "a"), copy.intent)
    }

    /** A failure means an empty History row has lost its Copy action: the History rows are not changed by the Home rule. */
    @Test
    fun `an empty History row keeps its copy action`() {
        val row = HistoryRow("e", "TIME-e", TranscriptionSource.SERVER, "")
        assertTrue("history: an empty row keeps its copy", historyCopyOf(row) != null)
    }
}
