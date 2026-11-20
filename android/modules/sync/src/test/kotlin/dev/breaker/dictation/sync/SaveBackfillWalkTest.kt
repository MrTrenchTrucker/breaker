package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bounded save-backfill walk over pending-save markers, hand-traced with lambdas. */
class SaveBackfillWalkTest {

    private companion object {
        const val THIS_TOKEN = 1_700_000_000_000L
        const val OLD_TOKEN = 1_600_000_000_000L
    }

    /** A stuck save: an old-token marker, row present -> re-queued and the marker cleared, nothing deferred. */
    @Test
    fun `a stuck save is re-queued and its marker cleared`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("save-me", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { id -> aTranscription(id) },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the stuck save is re-queued once", 1, walk.reQueued)
        assertEquals("android_sync: no enqueue failed", 0, walk.markerFailures)
        assertEquals("android_sync: nothing is deferred", 0, walk.deferred)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertEquals("android_sync: the row was enqueued", listOf("save-me"), enqueued.map { it.id })
        assertEquals("android_sync: the marker was cleared", listOf("save-me"), cleared)
    }

    /** An orphan marker: an old-token marker, row absent -> the marker is cleared, nothing re-queued, nothing deferred. */
    @Test
    fun `an orphan marker is cleared and nothing is re-queued`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("never-saved", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { null },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: an orphan is not re-queued", 0, walk.reQueued)
        assertEquals("android_sync: no enqueue failed", 0, walk.markerFailures)
        assertEquals("android_sync: nothing is deferred", 0, walk.deferred)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertTrue("android_sync: nothing was enqueued", enqueued.isEmpty())
        assertEquals("android_sync: the orphan marker was cleared", listOf("never-saved"), cleared)
    }

    /** An enqueue failure during the walk: an old-token marker, the marker stays and counts as a marker failure, nothing deferred. */
    @Test
    fun `an enqueue failure during the walk keeps the marker and counts it`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        val boom = RuntimeException("android_sync: planned enqueue failure")
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("save-me", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { id -> aTranscription(id) },
            enqueue = { enqueued.add(it); throw boom },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the failed enqueue is counted", 0, walk.reQueued)
        assertEquals("android_sync: the marker failure is counted", 1, walk.markerFailures)
        assertEquals("android_sync: nothing is deferred", 0, walk.deferred)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertTrue("android_sync: the marker was not cleared", cleared.isEmpty())
        assertEquals("android_sync: the row was offered once", 1, enqueued.size)
    }

    /** Paging: three old-token markers over two pages, the walk completes, nothing deferred. */
    @Test
    fun `the walk pages through all markers and completes`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        val allMarkers = listOf(PendingSaveMarker("a", OLD_TOKEN), PendingSaveMarker("b", OLD_TOKEN), PendingSaveMarker("c", OLD_TOKEN))
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { limit -> allMarkers.take(limit.coerceAtLeast(1)) },
            pendingSaveIdsPageAfter = { lastId, limit -> allMarkers.dropWhile { it.clientId != lastId }.drop(1).take(limit) },
            rowById = { id -> aTranscription(id) },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
            limit = 2,
        )
        assertEquals("android_sync: all three markers are re-queued", 3, walk.reQueued)
        assertEquals("android_sync: no marker failures", 0, walk.markerFailures)
        assertEquals("android_sync: nothing is deferred", 0, walk.deferred)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertEquals("android_sync: all three rows enqueued", listOf("a", "b", "c"), enqueued.map { it.id })
        assertEquals("android_sync: all three markers cleared", listOf("a", "b", "c"), cleared)
    }

    /** The page cap stops the walk and reports incomplete, nothing deferred. */
    @Test
    fun `the page cap stops the walk and reports incomplete`() {
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("a", OLD_TOKEN), PendingSaveMarker("b", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> listOf(PendingSaveMarker("c", OLD_TOKEN), PendingSaveMarker("d", OLD_TOKEN)) },
            rowById = { null },
            enqueue = { },
            clearPendingSave = { },
            limit = 2,
            maxPages = 1,
        )
        assertFalse("android_sync: the page cap stops the walk", walk.complete)
        assertEquals("android_sync: nothing failed", 0, walk.markerFailures)
        assertEquals("android_sync: nothing was re-queued", 0, walk.reQueued)
        assertEquals("android_sync: nothing is deferred", 0, walk.deferred)
    }
}
