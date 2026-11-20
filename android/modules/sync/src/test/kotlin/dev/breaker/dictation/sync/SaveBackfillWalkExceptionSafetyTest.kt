package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walk is exception-safe per marker: one throwing clear/read aborts neither the walk nor the later markers. */
class SaveBackfillWalkExceptionSafetyTest {

    private companion object {
        const val THIS_TOKEN = 1_700_000_000_000L
        const val OLD_TOKEN = 1_600_000_000_000L
    }

    /** A clear that throws on one marker does not stop the walk for the next marker: the failure is counted and the walk continues. */
    @Test
    fun `a clear that throws on one marker does not stop the walk for the next`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        var clearCalls = 0
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("first", OLD_TOKEN), PendingSaveMarker("second", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { id -> aTranscription(id) },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { id ->
                clearCalls += 1
                if (clearCalls == 1) throw RuntimeException("android_sync: planned clear failure")
                cleared.add(id)
            },
        )
        assertEquals("android_sync: both markers are enqueued once (the first before its clear threw)", 2, enqueued.size)
        assertEquals("android_sync: the first marker's clear failure is counted", 1, walk.markerFailures)
        assertEquals("android_sync: only the second marker fully re-queued (first clear threw)", 1, walk.reQueued)
        assertEquals("android_sync: nothing is deferred", 0, walk.deferred)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertEquals("android_sync: both rows were enqueued; the first clear threw after its enqueue", listOf("first", "second"), enqueued.map { it.id })
        assertEquals("android_sync: the second marker was cleared", listOf("second"), cleared)
    }

    /** A rowById that throws on one marker does not stop the walk for the next marker: the failure is counted and the walk continues. */
    @Test
    fun `a rowById that throws on one marker does not stop the walk for the next`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        var readCalls = 0
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("first", OLD_TOKEN), PendingSaveMarker("second", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { id ->
                readCalls += 1
                if (readCalls == 1) throw RuntimeException("android_sync: planned read failure")
                aTranscription(id)
            },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the first marker's read failure is counted", 1, walk.markerFailures)
        assertEquals("android_sync: nothing is deferred", 0, walk.deferred)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertEquals("android_sync: only the second row was enqueued; the first marker's row-read threw before its enqueue", listOf("second"), enqueued.map { it.id })
        assertEquals("android_sync: the second marker was cleared", listOf("second"), cleared)
    }
}
