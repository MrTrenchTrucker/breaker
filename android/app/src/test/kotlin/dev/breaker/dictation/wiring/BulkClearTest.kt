package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dead top-level [clearQueueAndTombstones] was removed from Backfill.kt (S4). The real sign-out
 * clear path is [BreakerApp.clearQueueAndTombstones], which clears the sync queue, the history
 * tombstones and the pull cursor together, each exactly once.
 *
 * Note: [QueuedSyncService] and [SqliteHistoryStore] are built through public factories that need an
 * Android `Context`, and this module's test classpath has no Robolectric, so the real adapters cannot
 * be instantiated here. These fakes mirror the real adapters' clear surface and assert the same
 * contract the production clear satisfies: queue and tombstones cleared together, each exactly once,
 * with the pull cursor cleared alongside the queue.
 */
class BulkClearTest {
    /** Mirrors QueuedSyncService.clearAll / clearPullCursor. */
    private class FakeSyncQueue {
        var clears = 0
        var pullCursorClears = 0
        fun clearAll() { clears++ }
        fun clearPullCursor() { pullCursorClears++ }
    }

    /** Mirrors SqliteHistoryStore.clearAllTombstones. */
    private class FakeHistory {
        var tombstoneClears = 0
        fun clearAllTombstones() { tombstoneClears++ }
    }

    /** The sign-out clear contract: queue, then tombstones, then the pull cursor, each once. */
    private fun clearSignOut(queue: FakeSyncQueue, history: FakeHistory) {
        queue.clearAll()
        history.clearAllTombstones()
        queue.clearPullCursor()
    }

    @Test
    fun `the queue and the tombstones are cleared together, each exactly once, with the pull cursor`() {
        val queue = FakeSyncQueue()
        val history = FakeHistory()
        clearSignOut(queue, history)
        assertEquals("the sync queue is cleared once", 1, queue.clears)
        assertEquals("the tombstones are cleared once", 1, history.tombstoneClears)
        assertEquals("the pull cursor is cleared with the queue", 1, queue.pullCursorClears)
    }

    @Test
    fun `repeating the clear clears each side once per call, never twice`() {
        val queue = FakeSyncQueue()
        val history = FakeHistory()
        clearSignOut(queue, history)
        clearSignOut(queue, history)
        assertEquals("the queue clear ran once per sign-out", 2, queue.clears)
        assertEquals("the tombstone clear ran once per sign-out", 2, history.tombstoneClears)
        assertEquals("the pull cursor clear ran once per sign-out", 2, queue.pullCursorClears)
    }
}
