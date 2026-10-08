package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history model run through its serial queue, first half: reads, taps, undo, the window end
 * and a failed read. The test drives both queues by hand: [QueueDispatcher] holds the store and
 * clipboard jobs until they are run, and [MainQueue] holds the draws until they are run.
 * Nothing here waits on a clock or a thread.
 */
class AsyncHistoryTest {
    private val a = transcription("a")
    private val b = transcription("b")

    @Test
    fun `nothing touches the store until the serial queue runs`() {
        val rig = Rig(listOf(a))
        rig.view.load()
        assertTrue("history: the store was touched before the serial queue ran", rig.events.isEmpty())
        assertTrue("history: a screen was drawn before the main queue ran", rig.drawn.isEmpty())
        rig.queue.runAll()
        assertEquals("history: the read did not reach the store once", listOf("list:50"), rig.events)
        rig.main.runAll()
        assertEquals("history: expected one screen drawn", 1, rig.drawn.size)
    }

    @Test
    fun `a read finishing after a newer read is dropped`() {
        val rig = Rig(listOf(a, b))
        rig.view.load()
        rig.queue.runOne() // the older read reads the store and posts its draw, with row a
        rig.store.rows.removeAll { it.id == "a" }
        rig.view.load()
        rig.queue.runAll()
        rig.main.runAll()
        assertEquals("history: expected exactly one screen drawn", 1, rig.drawn.size)
        assertEquals("history: expected two list calls", listOf("list:50", "list:50"), rig.events)
        assertEquals("history: the drawn screen is not the second read's", listOf("history.row.b"), rig.drawn[0].rowIds())
    }

    @Test
    fun `a read submitted before a tap is dropped and the tap result is drawn`() {
        val rig = Rig(listOf(a, b))
        rig.view.load()
        rig.view.tap(intent(HistoryActions.DELETE, "a"))
        rig.queue.runAll()
        rig.main.runAll()
        assertEquals("history: expected one screen drawn", 1, rig.drawn.size)
        assertNotNull("history: the undo block for a is not drawn", rig.drawn[0].node("history.undo.a"))
        assertNull("history: row a is still drawn with its undo block", rig.drawn[0].node("history.row.a"))
    }

    @Test
    fun `a read submitted before an undo is dropped`() {
        val rig = Rig(listOf(a, b))
        rig.prime()
        rig.view.tap(intent(HistoryActions.DELETE, "a"))
        rig.queue.runAll()
        rig.main.runAll()
        val before = rig.drawn.size
        rig.view.load()
        rig.view.tap(intent(HistoryActions.UNDO, "a"))
        rig.queue.runAll()
        rig.main.runAll()
        assertEquals("history: expected one screen drawn after the undo", 1, rig.drawn.size - before)
        val shown = rig.drawn.last()
        assertNotNull("history: row a is not back after the undo", shown.node("history.row.a"))
        assertNull("history: the undo block is still drawn after the undo", shown.node("history.undo.a"))
    }

    @Test
    fun `a read finishing after detach is dropped`() {
        val rig = Rig(listOf(a))
        rig.view.load()
        rig.view.close()
        rig.queue.runAll()
        rig.main.runAll()
        assertTrue("history: a screen was drawn after the view closed", rig.drawn.isEmpty())
    }

    @Test
    fun `a tap notice is not lost when a read is submitted after it`() {
        val rig = Rig(listOf(a))
        rig.prime()
        rig.view.tap(intent(HistoryActions.COPY, "a"))
        rig.view.load()
        rig.queue.runAll()
        rig.main.runAll()
        assertTrue("history: nothing was drawn", rig.drawn.isNotEmpty())
        assertEquals(
            "history: the first drawn screen lost the copied notice",
            HistoryTexts.NOTICE_COPIED,
            rig.drawn.first().notice(),
        )
    }

    @Test
    fun `closing with two pending deletes draws nothing and deletes both once in tap order`() {
        val rig = Rig(listOf(a, b))
        rig.prime()
        rig.view.tap(intent(HistoryActions.DELETE, "a"))
        rig.queue.runAll()
        rig.main.runAll()
        rig.view.tap(intent(HistoryActions.DELETE, "b"))
        rig.queue.runAll()
        rig.main.runAll()
        assertTrue("history: the store was touched by a delete before its window ended", rig.events.isEmpty())
        val drawnBefore = rig.drawn.size
        rig.view.close()
        assertTrue("history: close() touched the store before the serial queue ran", rig.events.isEmpty())
        rig.queue.runAll()
        rig.main.runAll()
        assertEquals(
            "history: the deletes did not run once each, in tap order",
            listOf("delete:a", "delete:b"),
            rig.events,
        )
        assertEquals("history: a screen was drawn after close()", drawnBefore, rig.drawn.size)
        rig.view.close()
        rig.queue.runAll()
        rig.main.runAll()
        assertEquals("history: a second close() changed the deletes", listOf("delete:a", "delete:b"), rig.events)
    }

    @Test
    fun `a second view on the same queue reads only after the deletes of the closed view`() {
        val rig = Rig(listOf(a, b))
        rig.prime()
        rig.view.tap(intent(HistoryActions.DELETE, "a"))
        rig.queue.runAll()
        rig.main.runAll()
        rig.view.close()
        val drawn2 = mutableListOf<Screen>()
        val view2 = rig.newView(drawn2)
        view2.load()
        rig.queue.runAll()
        rig.main.runAll()
        val deleteAt = rig.events.indexOf("delete:a")
        val lastList = rig.events.lastIndexOf("list:50")
        assertTrue(
            "history: the delete of a did not come before the second view's read, events were ${rig.events}",
            deleteAt >= 0 && deleteAt < lastList,
        )
        assertEquals("history: the second view did not draw exactly once", 1, drawn2.size)
        assertNull("history: row a is drawn by the second view", drawn2[0].node("history.row.a"))
    }

    @Test
    fun `a window that ends deletes on the serial queue and draws the result`() {
        val rig = Rig(listOf(a, b))
        rig.prime()
        rig.view.tap(intent(HistoryActions.DELETE, "a"))
        rig.queue.runAll()
        rig.main.runAll()
        rig.work.fire(rig.work.live().single())
        assertTrue("history: the delete ran on the caller when the window ended", rig.events.isEmpty())
        rig.queue.runAll()
        assertEquals(
            "history: the window end did not delete and then re-read, in order",
            listOf("delete:a", "list:50"),
            rig.events,
        )
        rig.main.runAll()
        assertEquals("history: expected the window end to draw once more", 2, rig.drawn.size)
        val shown = rig.drawn.last()
        assertNull("history: row a is still drawn after its window ended", shown.node("history.row.a"))
        assertNull("history: the undo block for a is still drawn after its window ended", shown.node("history.undo.a"))
    }

    @Test
    fun `a tap result still waiting when the view closes is not drawn`() {
        val rig = Rig(listOf(a))
        rig.prime()
        rig.view.tap(intent(HistoryActions.DELETE, "a"))
        rig.view.close()
        rig.queue.runAll()
        rig.main.runAll()
        assertTrue("history: a screen was drawn after the view closed", rig.drawn.isEmpty())
    }

    @Test
    fun `a failed read draws the list failure sentence`() {
        val rig = Rig(listOf(a))
        rig.store.failList = true
        rig.view.load()
        rig.queue.runAll()
        rig.main.runAll()
        assertEquals("history: expected one screen drawn", 1, rig.drawn.size)
        assertEquals(
            "history: the failed read did not draw the list failure sentence",
            HistoryTexts.NOTICE_LIST_FAILED,
            rig.drawn[0].notice(),
        )
    }
}
