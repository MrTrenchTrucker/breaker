package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeClipboardSink
import dev.breaker.dictation.ui.testing.FakeHistoryStore
import dev.breaker.dictation.ui.testing.HISTORY_FAILURE_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each tap asks of the store, the clipboard and the scheduler, and what the screen is
 * drawn from afterwards. Time is only ever moved by the test: a Delete schedules work into
 * [FakeDelayedWork], and the test decides when that work runs.
 */
class HistoryIntentHandlerTest {
    private val a = transcription("a")
    private val b = transcription("b")
    private val c = transcription("c")

    private class Scheduled(val delayMs: Long, val work: () -> Unit) {
        var cancelled: Boolean = false
        var ran: Boolean = false
    }

    /** Holds scheduled work until a test fires it. Nothing here waits on a clock or a thread. */
    private class FakeDelayedWork : DelayedWork {
        val windows: MutableList<Scheduled> = mutableListOf()

        override fun schedule(delayMs: Long, work: () -> Unit): Cancellation {
            val entry = Scheduled(delayMs, work)
            windows.add(entry)
            return Cancellation { entry.cancelled = true }
        }

        /** The windows that are neither cancelled nor run yet. */
        fun live(): List<Scheduled> = windows.filter { !it.cancelled && !it.ran }

        /** Runs [entry] as the scheduler would when its delay is up. A cancelled one does not run. */
        fun fire(entry: Scheduled) {
            if (entry.cancelled || entry.ran) return
            entry.ran = true
            entry.work()
        }
    }

    private class Fixture(vararg rows: Transcription) {
        val store = FakeHistoryStore(rows.toMutableList())
        val clipboard = FakeClipboardSink()
        val work = FakeDelayedWork()
        val drawn: MutableList<Screen> = mutableListOf()
        val handler = HistoryIntentHandler(store, clipboard, stamp, work, HistoryScreen())

        init {
            handler.onChange = { drawn.add(it) }
        }
    }

    private companion object {
        val stamp = TimestampFormat { "at $it" }

        fun transcription(
            id: String,
            text: String = "words $id",
            source: TranscriptionSource = TranscriptionSource.LOCAL,
        ) = Transcription(id = id, text = text, source = source, model = "tiny", durationMs = 1000L, createdAt = 1000L)
    }

    private fun tap(action: String, id: String = "") = ScreenIntent.History(action, id)

    private fun Screen.node(id: String): Node? = nodes.flatMap { it.flatten() }.firstOrNull { it.id == id }

    private fun Screen.notice(): String? = (node("history.notice") as? Label)?.text

    /** The ids of the row blocks drawn at the top level, in order. */
    private fun Screen.rowIds(): List<String> = nodes.map { it.id }.filter { it.startsWith("history.row.") }

    /** Every count a refused tap must leave unchanged. */
    private fun calls(f: Fixture): List<Any> = listOf(
        f.store.listCalls.toList(),
        f.store.deleted.toList(),
        f.clipboard.calls.toList(),
        f.work.windows.size,
        f.drawn.size,
    )

    @Test
    fun `the first page is read with a limit of fifty and rows carry their time`() {
        val f = Fixture(a, b)
        val s = f.handler.current()
        assertEquals("history: first read", listOf(50), f.store.listCalls)
        assertEquals("history: time from the format", "at 1000", (s.node("history.row.a.time") as Label).text)
    }

    @Test
    fun `Load more reads fifty more`() {
        val f = Fixture(a)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.MORE))
        assertEquals("history: second read", listOf(50, 100), f.store.listCalls)
    }

    @Test
    fun `the list never grows past three hundred and a further Load more reads nothing`() {
        val f = Fixture(*(1..400).map { transcription("r$it") }.toTypedArray())
        var s = f.handler.current()
        repeat(7) { s = f.handler.handle(tap(HistoryActions.MORE)) }
        assertEquals("history: reads up to the cap", listOf(50, 100, 150, 200, 250, 300), f.store.listCalls)
        assertNull("history: no Load more at the cap", s.node("history.more"))
        assertNotNull("history: limit note at the cap", s.node("history.limit"))
    }

    @Test
    fun `a Delete hides the row at once and leaves the store alone until the window ends`() {
        val f = Fixture(a, b)
        f.handler.current()
        val s = f.handler.handle(tap(HistoryActions.DELETE, "a"))
        assertTrue("history: nothing deleted at tap time", f.store.deleted.isEmpty())
        assertEquals("history: one window", 1, f.work.windows.size)
        assertEquals("history: window length", UNDO_WINDOW_MS, f.work.windows[0].delayMs)
        assertNull("history: row hidden", s.node("history.row.a"))
        assertNotNull("history: undo offered in its place", s.node("history.undo.a.button"))
    }

    @Test
    fun `Undo cancels the window and leaves the store and the list as they were`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        val s = f.handler.handle(tap(HistoryActions.UNDO, "a"))
        assertTrue("history: window cancelled", f.work.windows[0].cancelled)
        f.work.fire(f.work.windows[0])
        assertTrue("history: store untouched", f.store.deleted.isEmpty())
        assertEquals("history: no re-read after Undo", listOf(50), f.store.listCalls)
        assertEquals("history: both rows still in the store", 2, f.store.rows.size)
        assertNotNull("history: row back", s.node("history.row.a.delete"))
        assertNull("history: no undo block left", s.node("history.undo.a.button"))
    }

    @Test
    fun `when the window ends the row is deleted once and the screen is drawn again`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.work.fire(f.work.windows[0])
        assertEquals("history: one delete", listOf("a"), f.store.deleted)
        assertEquals("history: drawn once", 1, f.drawn.size)
        assertNull("history: row gone", f.drawn[0].node("history.row.a"))
        assertNotNull("history: other row kept", f.drawn[0].node("history.row.b.copy"))
    }

    @Test
    fun `a window that ends with the store refusing keeps the row and says so`() {
        val f = Fixture(a)
        f.store.refuseDelete = true
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.work.fire(f.work.windows[0])
        assertEquals("history: one attempt", listOf("a"), f.store.deleted)
        assertNotNull("history: row back", f.drawn[0].node("history.row.a.delete"))
        assertEquals("history: notice", HistoryTexts.NOTICE_DELETE_FAILED, f.drawn[0].notice())
    }

    @Test
    fun `a window whose delete throws keeps the row and shows only the fixed notice, never the failure text`() {
        val f = Fixture(a)
        f.store.failDelete = true
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.work.fire(f.work.windows[0])
        assertEquals("history: one attempt", listOf("a"), f.store.deleted)
        assertNotNull("history: row back", f.drawn[0].node("history.row.a.delete"))
        assertEquals("history: notice", HistoryTexts.NOTICE_DELETE_FAILED, f.drawn[0].notice())
        assertFalse("history: no marker in notice", f.drawn[0].notice().orEmpty().contains(HISTORY_FAILURE_MARKER))
    }

    @Test
    fun `close deletes each pending row once and leaves no live window`() {
        val f = Fixture(a, b, c)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.handler.handle(tap(HistoryActions.DELETE, "b"))
        f.handler.close()
        assertEquals("history: each pending row deleted once", listOf("a", "b"), f.store.deleted)
        assertTrue("history: nothing scheduled remains live", f.work.live().isEmpty())
        f.work.windows.forEach { f.work.fire(it) }
        f.handler.close()
        assertEquals("history: no second delete", listOf("a", "b"), f.store.deleted)
        assertTrue("history: no redraw from closed windows", f.drawn.isEmpty())
    }

    @Test
    fun `two quick deletes open two windows and each can be undone on its own`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.handler.handle(tap(HistoryActions.DELETE, "b"))
        assertEquals("history: two windows", 2, f.work.windows.size)
        f.handler.handle(tap(HistoryActions.UNDO, "a"))
        f.work.fire(f.work.windows[0])
        f.work.fire(f.work.windows[1])
        assertEquals("history: only the other row deleted", listOf("b"), f.store.deleted)
    }

    @Test
    fun `an Undo after its window ended, or a second Undo, makes no call`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.work.fire(f.work.windows[0])
        f.handler.handle(tap(HistoryActions.DELETE, "b"))
        f.handler.handle(tap(HistoryActions.UNDO, "b"))
        val before = calls(f)
        f.handler.handle(tap(HistoryActions.UNDO, "a"))
        f.handler.handle(tap(HistoryActions.UNDO, "b"))
        assertEquals("history: refused Undo changes nothing", before.take(4), calls(f).take(4))
    }

    @Test
    fun `a second Delete on a row already waiting opens no second window`() {
        val f = Fixture(a)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        assertEquals("history: one window", 1, f.work.windows.size)
    }

    @Test
    fun `an unknown id, action or row, and any other intent, are refused without a call`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        val before = calls(f)
        f.handler.handle(tap(HistoryActions.COPY, "zzz"))
        f.handler.handle(tap(HistoryActions.DELETE, "zzz"))
        f.handler.handle(tap(HistoryActions.UNDO, "zzz"))
        f.handler.handle(tap(HistoryActions.UNDO, "b"))
        f.handler.handle(tap(HistoryActions.COPY, "a"))
        f.handler.handle(tap("FLY", "b"))
        f.handler.handle(ScreenIntent.ToggleTheme)
        f.handler.handle(ScreenIntent.UseSystemTheme)
        f.handler.handle(ScreenIntent.SetRoutingMode("LOCAL"))
        f.handler.handle(ScreenIntent.SetSetting("preloadModel", "true"))
        f.handler.handle(ScreenIntent.Setup("recheck"))
        assertEquals("history: every refused tap changes nothing", before, calls(f))
    }

    @Test
    fun `a copy that works says so, once`() {
        val f = Fixture(a)
        f.handler.current()
        val s = f.handler.handle(tap(HistoryActions.COPY, "a"))
        assertEquals("history: copied text", listOf("words a"), f.clipboard.calls)
        assertEquals("history: copied notice", HistoryTexts.NOTICE_COPIED, s.notice())
        assertNull("history: notice drawn once", f.handler.handle(tap(HistoryActions.MORE)).notice())
    }

    @Test
    fun `a copy the clipboard refuses says so`() {
        val f = Fixture(a)
        f.clipboard.answer = false
        f.handler.current()
        val notice = f.handler.handle(tap(HistoryActions.COPY, "a")).notice()
        assertEquals("history: copy failed", HistoryTexts.NOTICE_COPY_FAILED, notice)
    }

    @Test
    fun `a copy that throws says so and shows no text or failure`() {
        val f = Fixture(a)
        f.clipboard.fail = true
        f.handler.current()
        val notice = f.handler.handle(tap(HistoryActions.COPY, "a")).notice().orEmpty()
        assertEquals("history: copy failed", HistoryTexts.NOTICE_COPY_FAILED, notice)
        assertFalse("history: no transcription text in a notice", notice.contains("words a"))
        assertFalse("history: no failure text in a notice", notice.contains(HISTORY_FAILURE_MARKER))
    }

    @Test
    fun `a delete that lands is not brought back when the re-read after it throws`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.store.failList = true
        f.work.fire(f.work.windows[0])
        assertEquals("history: one delete", listOf("a"), f.store.deleted)
        assertNull("history: deleted row not drawn after a failed re-read", f.drawn[0].node("history.row.a"))
        assertNotNull("history: other row kept", f.drawn[0].node("history.row.b.copy"))
    }

    @Test
    fun `a first read that throws shows the list failure sentence and no empty line or row`() {
        val f = Fixture(a, b)
        f.store.failList = true
        val s = f.handler.current()
        assertEquals("history: failure sentence", HistoryTexts.NOTICE_LIST_FAILED, s.notice())
        assertNull("history: no empty line after a failed first read", s.node("history.empty"))
        assertTrue("history: no rows after a failed first read", s.rowIds().isEmpty())
    }

    @Test
    fun `a Load more read that throws keeps the rows held and shows the sentence once`() {
        val f = Fixture(*(1..50).map { transcription("r$it") }.toTypedArray())
        f.handler.current()
        f.store.failList = true
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals("history: second read tried", listOf(50, 100), f.store.listCalls)
        assertEquals("history: rows kept", 50, s.rowIds().size)
        assertEquals("history: failure sentence", HistoryTexts.NOTICE_LIST_FAILED, s.notice())
        assertNull("history: sentence not drawn again without a read", f.handler.handle(ScreenIntent.ToggleTheme).notice())
    }

    @Test
    fun `a good read after a failed one draws the rows with neither the sentence nor a stale row`() {
        val f = Fixture(*(1..50).map { transcription("r$it") }.toTypedArray())
        f.handler.current()
        f.store.failList = true
        f.handler.handle(tap(HistoryActions.MORE))
        f.store.rows.removeAt(0)
        f.store.failList = false
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertEquals("history: rows read again", 49, s.rowIds().size)
        assertNull("history: the row removed from the store is not drawn", s.node("history.row.r1"))
        assertNull("history: no sentence after a good read", s.notice())
        assertNull("history: no empty line after a good read", s.node("history.empty"))
    }

    @Test
    fun `a window that ends with the store refusing hands the failure screen to onChange once`() {
        val f = Fixture(a)
        val changes = mutableListOf<Screen>()
        f.handler.onChange = { changes.add(it) }
        f.store.refuseDelete = true
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.work.fire(f.work.windows[0])
        assertEquals("history: onChange called once", 1, changes.size)
        assertEquals("history: delete-failed text in the change", HistoryTexts.NOTICE_DELETE_FAILED, changes[0].notice())
    }

    @Test
    fun `a good read after a failed first read draws the empty line and no sentence`() {
        val f = Fixture()
        f.store.failList = true
        f.handler.current()
        f.store.failList = false
        val s = f.handler.current()
        assertNotNull("history: empty line after a good read", s.node("history.empty"))
        assertNull("history: no sentence after a good read", s.notice())
    }

    @Test
    fun `the undo window is the literal five thousand milliseconds`() {
        assertEquals("history: window constant", 5000L, UNDO_WINDOW_MS)
    }

    @Test
    fun `a store of 280 rows ends at 280 rows with neither the limit note nor Load more`() {
        val f = Fixture(*(1..280).map { transcription("r$it") }.toTypedArray())
        var s = f.handler.current()
        repeat(5) { s = f.handler.handle(tap(HistoryActions.MORE)) }
        assertEquals("history: reads up to the cap", listOf(50, 100, 150, 200, 250, 300), f.store.listCalls)
        assertEquals("history: all 280 rows drawn", 280, s.rowIds().size)
        assertNull("history: no limit note on a short last page", s.node("history.limit"))
        assertNull("history: no Load more on a short last page", s.node("history.more"))
    }

    @Test
    fun `a store of exactly 300 rows ends with the limit note and no Load more`() {
        val f = Fixture(*(1..300).map { transcription("r$it") }.toTypedArray())
        var s = f.handler.current()
        repeat(5) { s = f.handler.handle(tap(HistoryActions.MORE)) }
        assertEquals("history: all 300 rows drawn", 300, s.rowIds().size)
        assertNotNull("history: limit note at the cap", s.node("history.limit"))
        assertNull("history: no Load more at the cap", s.node("history.more"))
    }

    @Test
    fun `a row deleted by close stays out of the screen when the next read throws`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.handler.close()
        f.store.failList = true
        val s = f.handler.current()
        assertEquals("history: deleted once", listOf("a"), f.store.deleted)
        assertNull("history: deleted row not drawn after a failed read", s.node("history.row.a"))
        assertNotNull("history: other row kept", s.node("history.row.b.copy"))
    }

    @Test
    fun `a delete refused inside close keeps the row when the next read throws`() {
        val f = Fixture(a, b)
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "a"))
        f.store.refuseDelete = true
        f.handler.close()
        f.store.failList = true
        val s = f.handler.current()
        assertEquals("history: one attempt", listOf("a"), f.store.deleted)
        assertNotNull("history: refused row drawn as a normal row", s.node("history.row.a.copy"))
    }

    @Test
    fun `Load more while a delete is pending keeps the row hidden and the window ends once`() {
        val f = Fixture(*(1..120).map { transcription("r$it") }.toTypedArray())
        f.handler.current()
        f.handler.handle(tap(HistoryActions.DELETE, "r1"))
        val s = f.handler.handle(tap(HistoryActions.MORE))
        assertNull("history: pending row not drawn as a row", s.node("history.row.r1.copy"))
        assertNotNull("history: undo offered while pending", s.node("history.undo.r1.button"))
        assertEquals("history: one window", 1, f.work.windows.size)
        f.work.fire(f.work.windows[0])
        assertEquals("history: one delete", listOf("r1"), f.store.deleted)
        assertNull("history: undo gone after the window", f.drawn.last().node("history.undo.r1.button"))
        assertNull("history: row gone after the window", f.drawn.last().node("history.row.r1.copy"))
    }
}
