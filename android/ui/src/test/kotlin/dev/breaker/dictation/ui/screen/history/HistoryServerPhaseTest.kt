package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderHistory
import dev.breaker.dictation.core.port.OlderResult
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeClipboardSink
import dev.breaker.dictation.ui.testing.FakeHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second phase of Load more, through the handler's real press path and the real screen: when the
 * server is asked, with which bound, what is drawn, and what each answer does. Every older-rows answer
 * is scripted; nothing waits on a clock or a thread.
 */
class HistoryServerPhaseTest {
    private class Window(val work: () -> Unit) {
        var cancelled: Boolean = false
        var ran: Boolean = false
    }

    /** Holds scheduled work until a test fires it. */
    private class Windows : DelayedWork {
        val all: MutableList<Window> = mutableListOf()

        override fun schedule(delayMs: Long, work: () -> Unit): Cancellation {
            val window = Window(work)
            all.add(window)
            return Cancellation { window.cancelled = true }
        }

        fun live(): List<Window> = all.filter { !it.cancelled && !it.ran }

        fun fire(window: Window) {
            if (window.cancelled || window.ran) return
            window.ran = true
            window.work()
        }
    }

    /** An older-rows port that answers from a script and records every call. */
    private class ScriptedOlder(var offered: Boolean = true) : OlderHistory {
        val bounds: MutableList<OlderBound> = mutableListOf()
        val deletes: MutableList<String> = mutableListOf()
        val answers: MutableList<OlderResult> = mutableListOf()
        var deleteAnswer: Boolean = true
        var failRead: Boolean = false

        override fun available(): Boolean = offered

        override fun older(bound: OlderBound): OlderResult {
            bounds.add(bound)
            if (failRead) throw IllegalStateException("read failed")
            return answers.removeAt(0)
        }

        override fun delete(id: String): Boolean {
            deletes.add(id)
            return deleteAnswer
        }
    }

    private class Fixture(held: List<Transcription>, val older: ScriptedOlder?) {
        val store = FakeHistoryStore(held.toMutableList())
        val clipboard = FakeClipboardSink()
        val work = Windows()
        val drawn: MutableList<Screen> = mutableListOf()
        val handler = HistoryIntentHandler(store, clipboard, stamp, work, HistoryScreen(), older)

        init {
            handler.onChange = { drawn.add(it) }
        }
    }

    private companion object {
        val stamp = TimestampFormat { "at $it" }

        fun row(id: String, createdAt: Long) =
            Transcription(id = id, text = "words $id", source = TranscriptionSource.LOCAL, model = "tiny", durationMs = 1000L, createdAt = createdAt)

        fun page(vararg rows: Transcription, next: OlderBound = OlderBound(500L, 7L), more: Boolean = true, skipped: Int = 0) =
            OlderResult.Page(rows = rows.toList(), skipped = skipped, next = next, more = more)

        val threeHeld = listOf(row("a", 3000L), row("b", 2000L), row("c", 1000L))
    }

    private fun tap(action: String, id: String = "") = ScreenIntent.History(action, id)

    private fun Screen.node(id: String): Node? = nodes.flatMap { it.flatten() }.firstOrNull { it.id == id }

    private fun Screen.notice(): String? = (node("history.notice") as? Label)?.text

    private fun Screen.rowIds(): List<String> = nodes.map { it.id }.filter { it.startsWith("history.row.") }

    private fun loaded(held: List<Transcription>, older: ScriptedOlder?): Fixture {
        val f = Fixture(held, older)
        f.handler.current()
        return f
    }

    @Test
    fun `with no older port a full page still shows Load more and a short page shows none`() {
        val full = loaded((1..50).map { row("r$it", 100_000L - it) }, null)
        assertEquals(LoadMorePhase.LOCAL, full.handler.serverPhase)
        assertNotNull(full.handler.handle(tap(HistoryActions.SHOW_ALL, "none")).node("history.more"))
        val short = loaded(threeHeld, null)
        assertEquals(LoadMorePhase.LOCAL, short.handler.serverPhase)
        assertNull(short.handler.handle(tap(HistoryActions.SHOW_ALL, "none")).node("history.more"))
    }

    @Test
    fun `a full local page keeps the local phase and a press reads the store and not the server`() {
        val older = ScriptedOlder()
        val f = loaded((1..120).map { row("r$it", 100_000L - it) }, older)
        assertEquals(LoadMorePhase.LOCAL, f.handler.serverPhase)
        f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(listOf(50, 100), f.store.listCalls)
        assertTrue(older.bounds.isEmpty())
    }

    @Test
    fun `a short local list offers the server button`() {
        val f = loaded(threeHeld, ScriptedOlder())
        assertEquals(LoadMorePhase.SERVER, f.handler.serverPhase)
        assertNotNull(f.handler.handle(tap(HistoryActions.SHOW_ALL, "none")).node("history.more"))
    }

    @Test
    fun `the server is not offered when the port says it is not available`() {
        val older = ScriptedOlder(offered = false)
        val f = loaded(threeHeld, older)
        assertEquals(LoadMorePhase.LOCAL, f.handler.serverPhase)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertNull(s.node("history.more"))
        assertTrue(older.bounds.isEmpty())
    }

    @Test
    fun `an empty local list shows no button and makes no server call`() {
        val older = ScriptedOlder()
        val f = loaded(emptyList(), older)
        assertEquals(LoadMorePhase.LOCAL, f.handler.serverPhase)
        f.handler.handle(tap(HistoryActions.MORE))
        assertTrue(older.bounds.isEmpty())
    }

    @Test
    fun `the first server press sends the createdAt of the last held row and shows the rows below`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L)))
        val f = loaded(threeHeld, older)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(listOf(OlderBound(1000L, null)), older.bounds)
        assertEquals(listOf("history.row.a", "history.row.b", "history.row.c", "history.row.s1"), s.rowIds())
    }

    @Test
    fun `the next server press sends the previous next bound unchanged`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L), next = OlderBound(500L, 7L)))
        older.answers.add(page(row("s2", 400L), next = OlderBound(400L, 3L)))
        val f = loaded(threeHeld, older)
        f.handler.handle(tap(HistoryActions.MORE))
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(listOf(OlderBound(1000L, null), OlderBound(500L, 7L)), older.bounds)
        assertEquals(listOf("history.row.a", "history.row.b", "history.row.c", "history.row.s1", "history.row.s2"), s.rowIds())
    }

    @Test
    fun `a server row that is already held is not drawn twice`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("b", 2000L), row("s1", 500L)))
        val f = loaded(threeHeld, older)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(listOf("history.row.a", "history.row.b", "history.row.c", "history.row.s1"), s.rowIds())
    }

    @Test
    fun `an answer of none older takes the button away for the visit`() {
        val older = ScriptedOlder()
        older.answers.add(OlderResult.NoneOlder)
        val f = loaded(threeHeld, older)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(LoadMorePhase.GONE, f.handler.serverPhase)
        assertNull(s.node("history.more"))
        assertNull(s.notice())
    }

    @Test
    fun `a last page takes the button away for the visit`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L), more = false))
        val f = loaded(threeHeld, older)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(LoadMorePhase.GONE, f.handler.serverPhase)
        assertNull(s.node("history.more"))
        assertEquals(listOf("history.row.a", "history.row.b", "history.row.c", "history.row.s1"), s.rowIds())
    }

    @Test
    fun `three hundred server rows replace the button with the cap note and no further read happens`() {
        val older = ScriptedOlder()
        for (p in 1..6) {
            older.answers.add(page(*(1..50).map { row("s${p}x$it", 500L - p * 50 - it) }.toTypedArray(), next = OlderBound(500L - p, p.toLong())))
        }
        val f = loaded(threeHeld, older)
        repeat(5) { f.handler.handle(tap(HistoryActions.MORE)) }
        assertEquals(LoadMorePhase.SERVER, f.handler.serverPhase)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(LoadMorePhase.CAP, f.handler.serverPhase)
        assertEquals(HistoryTexts.SERVER_CAP_NOTE, (s.node("history.limit") as Label).text)
        assertNull(s.node("history.more"))
        f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(6, older.bounds.size)
    }

    @Test
    fun `each failure kind shows its own notice, keeps the list and keeps the button`() {
        val cases = listOf(
            OlderResult.SignedOut to HistoryTexts.NOTICE_SIGNED_OUT,
            OlderResult.NotAllowed to HistoryTexts.NOTICE_SERVER_UNAVAILABLE,
            OlderResult.Unavailable to HistoryTexts.NOTICE_SERVER_UNAVAILABLE,
            OlderResult.Busy(retryAfterSeconds = 30) to HistoryTexts.NOTICE_SERVER_BUSY,
            OlderResult.BadRequest to HistoryTexts.NOTICE_LIST_FAILED,
        )
        for ((answer, expected) in cases) {
            val older = ScriptedOlder()
            older.answers.add(answer)
            val f = loaded(threeHeld, older)
            val s = f.handler.handle(tap(HistoryActions.MORE))
            assertEquals("notice for $answer", expected, s.notice())
            assertEquals("list for $answer", listOf("history.row.a", "history.row.b", "history.row.c"), s.rowIds())
            assertEquals("phase for $answer", LoadMorePhase.SERVER, f.handler.serverPhase)
        }
    }

    @Test
    fun `a page where every row was unreadable shows a notice and keeps the button`() {
        val older = ScriptedOlder()
        older.answers.add(page(skipped = 3, more = true))
        val f = loaded(threeHeld, older)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(HistoryTexts.NOTICE_SERVER_EMPTY_MORE, s.notice())
        assertEquals(LoadMorePhase.SERVER, f.handler.serverPhase)
    }

    @Test
    fun `a port that throws is read as unavailable`() {
        val older = ScriptedOlder()
        older.failRead = true
        val f = loaded(threeHeld, older)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(HistoryTexts.NOTICE_SERVER_UNAVAILABLE, s.notice())
        assertEquals(LoadMorePhase.SERVER, f.handler.serverPhase)
    }

    @Test
    fun `deleting a server row asks the port, drops the row and a later page does not bring it back`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L)))
        older.answers.add(page(row("s1", 500L), row("s2", 400L), next = OlderBound(400L, 3L)))
        val f = loaded(threeHeld, older)
        f.handler.handle(tap(HistoryActions.MORE))
        f.handler.handle(tap(HistoryActions.DELETE, "s1"))
        assertEquals(1, f.work.live().size)
        f.work.fire(f.work.live()[0])
        assertEquals(listOf("s1"), older.deletes)
        assertTrue(f.store.deleted.isEmpty())
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(listOf("history.row.a", "history.row.b", "history.row.c", "history.row.s2"), s.rowIds())
    }

    @Test
    fun `a refused server delete puts the row back with the failure notice`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L)))
        older.deleteAnswer = false
        val f = loaded(threeHeld, older)
        f.handler.handle(tap(HistoryActions.MORE))
        f.handler.handle(tap(HistoryActions.DELETE, "s1"))
        f.work.fire(f.work.live()[0])
        val s = f.drawn.last()
        assertEquals(HistoryTexts.NOTICE_DELETE_FAILED, s.notice())
        assertTrue(s.rowIds().contains("history.row.s1"))
    }

    @Test
    fun `closing commits each pending delete once through the store that owns the row`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L)))
        val f = loaded(threeHeld, older)
        f.handler.handle(tap(HistoryActions.MORE))
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.handler.handle(tap(HistoryActions.DELETE, "s1"))
        assertEquals(2, f.work.live().size)
        f.handler.close()
        assertEquals(listOf("a"), f.store.deleted)
        assertEquals(listOf("s1"), older.deletes)
        assertTrue(f.work.live().isEmpty())
    }

    @Test
    fun `a server row can be copied`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L)))
        val f = loaded(threeHeld, older)
        f.handler.handle(tap(HistoryActions.MORE))
        val s = f.handler.handle(tap(HistoryActions.COPY, "s1"))
        assertEquals(listOf("words s1"), f.clipboard.calls)
        assertEquals(HistoryTexts.NOTICE_COPIED, s.notice())
    }

    @Test
    fun `a server row that is still shown is not drawn twice when a later page sends it again`() {
        val older = ScriptedOlder()
        older.answers.add(page(row("s1", 500L), next = OlderBound(500L, 7L)))
        older.answers.add(page(row("s1", 500L), row("s2", 400L), next = OlderBound(400L, 3L)))
        val f = loaded(threeHeld, older)
        f.handler.handle(tap(HistoryActions.MORE))
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals(listOf("history.row.a", "history.row.b", "history.row.c", "history.row.s1", "history.row.s2"), s.rowIds())
    }

    @Test
    fun `a last page where every row was unreadable ends the list quietly`() {
        val older = ScriptedOlder()
        older.answers.add(page(skipped = 3, more = false))
        val f = loaded(threeHeld, older)
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertNull(s.notice())
        assertEquals(LoadMorePhase.GONE, f.handler.serverPhase)
        assertNull(s.node("history.more"))
    }

    @Test
    fun `the server button reports the MORE action`() {
        val f = loaded(threeHeld, ScriptedOlder())
        val s = f.handler.handle(tap(HistoryActions.SHOW_ALL, "none"))
        assertEquals(ScreenIntent.History(HistoryActions.MORE), (s.node("history.more") as Action).intent)
    }
}
