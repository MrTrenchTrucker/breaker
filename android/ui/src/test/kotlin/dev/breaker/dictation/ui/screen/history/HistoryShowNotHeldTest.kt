package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.testing.FakeClipboardSink
import dev.breaker.dictation.ui.testing.FakeHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Show all for a row that is not held changes nothing, not even later: the id is not kept, so a
 * row with that id that a later read brings in is drawn closed. Show all is sent alone here, with
 * no Hide after it, so nothing else can take the id back out.
 */
class HistoryShowNotHeldTest {

    private fun toggleOf(s: Screen, id: String): Action =
        s.node("history.row.$id.toggle") as? Action ?: throw AssertionError("history: no toggle of $id")

    private fun textOf(s: Screen, id: String): CollapsibleText =
        s.node("history.row.$id.text") as? CollapsibleText ?: throw AssertionError("history: no text of $id")

    @Test
    fun `show all for a row not held is not kept for a row a later read brings in`() {
        val store = FakeHistoryStore(mutableListOf(transcription("a")))
        val handler = HistoryIntentHandler(store, FakeClipboardSink(), stamp, FakeDelayedWork(), HistoryScreen())
        handler.current()
        handler.handle(intent(HistoryActions.SHOW_ALL, "b"))
        store.save(transcription("b"))
        val later = handler.current()
        assertFalse("history: the row read in later is closed", textOf(later, "b").opened)
        assertEquals("history: its toggle reads Show all", HistoryTexts.BUTTON_SHOW_ALL, toggleOf(later, "b").text)
    }
}
