package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderHistory
import dev.breaker.dictation.core.port.OlderResult
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.testing.FakeClipboardSink
import dev.breaker.dictation.ui.testing.FakeHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The async model hands its older-rows port to the handler: the server phase is reachable through it. */
class AsyncHistoryOlderTest {
    /** Answers one page and records the bounds it was asked for. */
    private class OnePageOlder : OlderHistory {
        val bounds: MutableList<OlderBound> = mutableListOf()

        override fun available(): Boolean = true

        override fun older(bound: OlderBound): OlderResult {
            bounds.add(bound)
            return OlderResult.Page(
                rows = listOf(transcription("s1")),
                skipped = 0,
                next = OlderBound(500L, 7L),
                more = true,
            )
        }

        override fun delete(id: String): Boolean = true
    }

    @Test
    fun `a view built with an older port offers the server button and a press reaches the port`() {
        val older = OnePageOlder()
        val queue = QueueDispatcher()
        val main = MainQueue()
        val drawn: MutableList<Screen> = mutableListOf()
        val store = FakeHistoryStore(mutableListOf(transcription("a"), transcription("b")))
        val view = AsyncHistory(
            store, FakeClipboardSink(), stamp, FakeDelayedWork(), HistoryScreen(), queue, { main.post(it) }, { drawn.add(it) }, older,
        )
        view.load()
        queue.runAll()
        main.runAll()
        assertNotNull(drawn.last().node("history.more"))
        view.tap(intent(HistoryActions.MORE))
        queue.runAll()
        main.runAll()
        assertEquals(listOf(OlderBound(1000L, null)), older.bounds)
        assertTrue(drawn.last().rowIds().contains("history.row.s1"))
    }
}
