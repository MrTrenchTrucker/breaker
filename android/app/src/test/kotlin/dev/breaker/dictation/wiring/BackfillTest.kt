package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackfillTest {
    @Test
    fun `backfill enqueues a delete for every tombstone not already queued as a delete`() {
        val enqueued = mutableListOf<String>()
        val deleteQueued = mutableSetOf<String>()
        backfillPendingTombstones(
            pendingTombstoneIds = { listOf("t1", "t2", "t3") },
            isDeleteQueued = { id -> id in deleteQueued },
            enqueueDelete = { enqueued.add(it) },
        )
        assertEquals("no tombstone was queued as a delete", listOf("t1", "t2", "t3"), enqueued)
    }

    @Test
    fun `a queued save never blocks the delete`() {
        // Regression for B5: a pre-upgrade queued SAVE of X must not stop the tombstone of X
        // from being queued as a delete. isDeleteQueued reports false for a queued save, so
        // the backfill enqueues the delete even though a save of the same id is queued.
        val enqueued = mutableListOf<String>()
        backfillPendingTombstones(
            pendingTombstoneIds = { listOf("x") },
            isDeleteQueued = { false }, // X is NOT queued as a delete (it only has a queued save)
            enqueueDelete = { enqueued.add(it) },
        )
        assertEquals("the delete is queued even though a save of the same id is queued", listOf("x"), enqueued)
    }

    @Test
    fun `an id already queued as a delete is not re-enqueued`() {
        val enqueued = mutableListOf<String>()
        val deleteQueued = mutableSetOf("t1")
        backfillPendingTombstones(
            pendingTombstoneIds = { listOf("t1", "t2") },
            isDeleteQueued = { id -> id in deleteQueued },
            enqueueDelete = { enqueued.add(it); deleteQueued.add(it) },
        )
        assertEquals("only the tombstone not already queued as a delete is enqueued", listOf("t2"), enqueued)
    }

    @Test
    fun `a second backfill is a no-op once every tombstone is queued as a delete`() {
        val enqueued = mutableListOf<String>()
        val deleteQueued = mutableSetOf<String>()
        backfillPendingTombstones(
            pendingTombstoneIds = { listOf("t1", "t2") },
            isDeleteQueued = { id -> id in deleteQueued },
            enqueueDelete = { enqueued.add(it); deleteQueued.add(it) },
        )
        assertEquals("first run queues both tombstones", listOf("t1", "t2"), enqueued)
        enqueued.clear()
        backfillPendingTombstones(
            pendingTombstoneIds = { listOf("t1", "t2") },
            isDeleteQueued = { id -> id in deleteQueued },
            enqueueDelete = { enqueued.add(it) },
        )
        assertTrue("second run enqueues nothing: every id is already queued as a delete", enqueued.isEmpty())
    }

    @Test
    fun `an empty tombstone list enqueues nothing`() {
        val enqueued = mutableListOf<String>()
        backfillPendingTombstones(
            pendingTombstoneIds = { emptyList() },
            isDeleteQueued = { false },
            enqueueDelete = { enqueued.add(it) },
        )
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `the paged backfill queues every tombstone across all pages, past the first 1000`() {
        val enqueued = mutableListOf<String>()
        val page1 = (0 until 1000).map { "tomb-%04d".format(it) }
        val page2 = (0 until 200).map { "extra-%04d".format(it) }
        var firstAfterId: String? = null
        var afterPages = 0
        backfillAllUserTombstones(
            pendingIds = { limit -> page1.take(limit) },
            pendingIdsAfter = { lastId, limit ->
                if (firstAfterId == null) firstAfterId = lastId
                afterPages++
                if (afterPages == 1) page2.take(limit) else error("the short page is the last page")
            },
            isDeleteQueued = { false },
            enqueueDelete = { enqueued.add(it) },
        )
        assertEquals("every pending tombstone across all pages gets a delete", page1.size + page2.size, enqueued.size)
        assertEquals(page1 + page2, enqueued)
        assertEquals("page 2 starts after the last id of page 1", page1.last(), firstAfterId)
        // Page 2 is short (200 < 1000), so it is the last page: the walk stops after it with no
        // extra empty lookup.
        assertEquals("the short page 2 stops the walk, no extra lookup", 1, afterPages)
    }

    @Test
    fun `the walk stops on a short page without one more empty lookup`() {
        // A page with fewer than [limit] ids is the last page. The walk must stop
        // after processing it and not make one more lookup that would come back empty. The fake
        // counts its calls and honours [limit]; a wrong limit would change the page size and go red.
        val enqueued = mutableListOf<String>()
        var calls = 0
        val fullPage = (0 until 1000).map { "t-%04d".format(it) }
        val shortPage = (0 until 25).map { "s-%04d".format(it) }
        backfillAllUserTombstones(
            limit = 1000,
            pendingIds = { limit -> fullPage.take(limit) },
            pendingIdsAfter = { _, limit ->
                calls++
                when (calls) {
                    1 -> shortPage.take(limit)
                    else -> error("the walk must not look past the short page")
                }
            },
            isDeleteQueued = { false },
            enqueueDelete = { enqueued.add(it) },
        )
        assertEquals("the full page then the short page, each honouring the limit", fullPage.size + shortPage.size, enqueued.size)
        assertEquals("the walk stopped after the short page, no extra lookup", 1, calls)
    }

    @Test
    fun `the page cap stops the walk instead of paging for ever`() {
        val enqueued = mutableListOf<String>()
        val fullPage = (0 until 1000).map { "tomb-%04d".format(it) }
        val pages = backfillAllUserTombstones(
            pendingIds = { _ -> fullPage },
            pendingIdsAfter = { _, _ -> fullPage }, // always another full page: infinite without the cap
            isDeleteQueued = { false },
            enqueueDelete = { enqueued.add(it) },
            maxPages = 4,
        )
        assertEquals("the cap stops the walk after maxPages pages", 4 * 1000, pages)
        assertEquals(4 * 1000, enqueued.size)
    }

    @Test
    fun `boundedPush returns once the push has finished`() {
        // Latch-based, not wall-clock-only: the push signals completion, and boundedPush returns
        // only after the worker thread joins, so a latch proves both that the push ran and that
        // boundedPush returned. Each push runs on its own thread, so this test is independent of
        // any other push (no shared pool).
        val done = CountDownLatch(1)
        boundedPush({ Thread.sleep(30); done.countDown() }, 1_000)
        assertTrue("the push ran and signalled completion before boundedPush returned",
            done.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a hanging push does not block the caller past the bound`() {
        // Independent of any shared pool: the hanging push runs on its own daemon thread, so a
        // second boundedPush on another thread is never queued behind it. A latch proves the
        // hanging push started; boundedPush must return before that push finishes.
        val pushStarted = CountDownLatch(1)
        val pushFinished = CountDownLatch(1)
        boundedPush({
            pushStarted.countDown()
            Thread.sleep(6_000) // hangs well past the 100 ms bound
            pushFinished.countDown()
        }, 100)
        assertTrue("boundedPush returned within the bound", pushStarted.await(1, TimeUnit.SECONDS))
        assertFalse("boundedPush moved on while the hanging push keeps running",
            pushFinished.await(1, TimeUnit.SECONDS))
        // A second push on its own thread is never starved by the hanging one.
        val second = CountDownLatch(1)
        boundedPush({ second.countDown() }, 1_000)
        assertTrue("a later push is never starved by the hanging one (own thread)",
            second.await(2, TimeUnit.SECONDS))
    }

    private class ClearRig {
        var markerSet = false
        val cleared = mutableListOf<String>()
        fun clearMarkerIsSet() = markerSet
        fun clearQueue() { cleared.add("queue") }
        fun clearTombstones() { cleared.add("tombstones") }
        fun clearPullCursor() { cleared.add("cursor") }
        fun clearMarker() { cleared.add("marker"); markerSet = false }
    }

    @Test
    fun `marker set at start-up finishes the clear and skips the backfill`() {
        val rig = ClearRig().apply { markerSet = true }
        val finished = finishClearIfNeeded(
            clearMarkerIsSet = rig::clearMarkerIsSet,
            clearQueue = rig::clearQueue,
            clearTombstones = rig::clearTombstones,
            clearPullCursor = rig::clearPullCursor,
            clearMarker = rig::clearMarker,
        )
        assertTrue("start-up found the marker and finished the clear", finished)
        assertEquals("queue, tombstones, pull cursor, then the marker removed",
            listOf("queue", "tombstones", "cursor", "marker"), rig.cleared)
        assertFalse("the marker was removed", rig.markerSet)
        assertFalse("marker set: the backfill gate is closed this start", !finished)
    }

    @Test
    fun `no marker at start-up nothing is cleared and the backfill runs`() {
        val rig = ClearRig()
        val finished = finishClearIfNeeded(
            clearMarkerIsSet = rig::clearMarkerIsSet,
            clearQueue = rig::clearQueue,
            clearTombstones = rig::clearTombstones,
            clearPullCursor = rig::clearPullCursor,
            clearMarker = rig::clearMarker,
        )
        assertFalse("no marker: nothing is finished", finished)
        assertTrue("nothing was cleared", rig.cleared.isEmpty())
        assertTrue("the marker stays absent", !rig.markerSet)
        assertTrue("no marker: the backfill gate is open", !finished)
    }

    @Test
    fun `a crash mid-clear leaves the marker set and the next start finishes it`() {
        val rig = ClearRig().apply { markerSet = true }
        val boom = IllegalStateException("the clear broke")
        val threw = try {
            finishClearIfNeeded(
                clearMarkerIsSet = rig::clearMarkerIsSet,
                clearQueue = { rig.clearQueue(); throw boom },
                clearTombstones = rig::clearTombstones,
                clearPullCursor = rig::clearPullCursor,
                clearMarker = rig::clearMarker,
            )
            false
        } catch (e: IllegalStateException) {
            assertSame("the crash propagates", boom, e)
            true
        }
        assertTrue("the crash is not swallowed", threw)
        assertEquals("the queue was cleared before the crash", listOf("queue"), rig.cleared)
        assertTrue("the marker is still set: the next start finishes the clear", rig.markerSet)
        val finished = finishClearIfNeeded(
            clearMarkerIsSet = rig::clearMarkerIsSet,
            clearQueue = rig::clearQueue,
            clearTombstones = rig::clearTombstones,
            clearPullCursor = rig::clearPullCursor,
            clearMarker = rig::clearMarker,
        )
        assertTrue("the next start finishes the clear", finished)
        assertEquals("the clear completed on the second start",
            listOf("queue", "queue", "tombstones", "cursor", "marker"), rig.cleared)
        assertFalse("the marker was removed on the second start", rig.markerSet)
    }

    @Test
    fun `sync off the backfill seam is never called`() {
        var called = false
        runBackfillWhenAllowed(syncOn = false) { called = true }
        assertFalse("sync off: the backfill seam does not run and no queue file is touched", called)
        called = false
        runBackfillWhenAllowed(syncOn = true) { called = true }
        assertTrue("sync on: the backfill seam runs", called)
    }
}
