package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last-transcript box of the home screen, drawn as the shared outlined card: a header row with the
 * heading at the left and the time at the right, then the text, then the buttons with Copy raised.
 */
class HomeTranscriptCardTest {
    private val screen = HomeScreen(TimestampFormat { "at $it" })

    private val full = HomeState(
        switchOn = true,
        modelReady = true,
        lastDictationAt = 1L,
        lastTranscript = "Running about twenty minutes late,",
        transcriptTime = "06:42",
    )

    private fun top(state: HomeState): List<Node> = screen.render(state).nodes

    private fun all(state: HomeState): List<Node> = top(state).flatMap { it.flatten() }

    private inline fun <reified T : Node> nodeOf(state: HomeState, id: String): T =
        all(state).filterIsInstance<T>().first { it.id == id }

    @Test
    fun `the last transcript box is the shared card outlined in the accent on a surface`() {
        val box = nodeOf<Box>(full, HomeNodeIds.TRANSCRIPT)
        assertEquals("home: the transcript outline is the accent", PaletteSlot.ACCENT, box.outline)
        assertEquals("home: the transcript fill is the surface", PaletteSlot.SURFACE, box.background)
        assertEquals("home: the transcript pad is L", Space.L, box.pad)
        assertEquals("home: the transcript gap is S", Space.S, box.gap)
    }

    @Test
    fun `the card holds the header row, then the text, then the buttons`() {
        val box = nodeOf<Box>(full, HomeNodeIds.TRANSCRIPT)
        assertEquals(
            "home: the card's children in order",
            listOf("home.transcript.header", "home.transcript.text", "home.transcript.buttons"),
            box.children.map { it.id },
        )
    }

    @Test
    fun `the header row is a spread row with the heading in capitals and the time after it`() {
        val header = nodeOf<Box>(full, "home.transcript.header")
        assertTrue("home: the header is a row", header.horizontal)
        assertTrue("home: the header spreads the heading left and the time right", header.spread)
        val word = header.children[0] as Label
        assertEquals("home: the heading is the transcript heading word", "home.transcript.word", word.id)
        assertTrue("home: the heading is in capitals", word.caps)
        assertEquals("home: the heading is muted", PaletteSlot.TEXT_MUTED, word.color)
        assertEquals("home: the heading is the body role", TypeRole.BODY, word.role)
        assertEquals("home: the time follows the heading", "home.transcript.time", header.children[1].id)
        assertEquals("home: the time is mono", TypeRole.MONO, nodeOf<Label>(full, "home.transcript.time").role)
    }

    @Test
    fun `the header holds the heading only when there is no time`() {
        val quiet = HomeState()
        assertEquals("home: the header holds the heading only", 1, nodeOf<Box>(quiet, "home.transcript.header").children.size)
        assertFalse("home: no time node when there is none", all(quiet).any { it.id == "home.transcript.time" })
    }

    @Test
    fun `the copy button is raised and sits first in a spread buttons row`() {
        val buttons = nodeOf<Box>(full, "home.transcript.buttons")
        assertTrue("home: the buttons are a spread row", buttons.horizontal && buttons.spread)
        val copy = buttons.children[0] as Action
        assertEquals("home: the copy button is first", "home.transcript.copy", copy.id)
        assertEquals("home: the copy button is the raised kit button", KitLook.RAISED, copy.kit)
    }
}
