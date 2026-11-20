package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Condition 2, mechanism (b): the walk compares run tokens by EQUALITY.
 *
 * A marker stamped with this process's run token is live: the walk leaves it, counts it as
 * deferred and discards nothing - even when its row is absent because the save of this run is
 * still in flight (case (i)). The row then commits; if the save-path enqueue fails, the marker
 * is still there for the next start.
 *
 * Markers of any other token are old: row absent -> discarded (case (ii)); row present ->
 * re-queued (case (iii)).
 *
 * Mutant: a walk that ignores the token and treats every marker as old makes
 * `a current-token marker is left and counted as deferred, nothing discarded` go RED by name,
 * because it clears the live marker and counts deferred = 0 instead of leaving it with
 * deferred = 1.
 */
class SaveBackfillWalkRaceTest {

    private companion object {
        const val THIS_TOKEN = 1_700_000_000_000L
        const val OLD_TOKEN = 1_600_000_000_000L
    }

    /** Case (i), first half: a current-token marker with the row absent mid-save is left, deferred is counted, nothing is discarded. */
    @Test
    fun `a current-token marker is left and counted as deferred, nothing discarded`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("live", THIS_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { null },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the current-token marker is left, not enqueued", 0, walk.reQueued)
        assertEquals("android_sync: the current-token marker is counted as deferred", 1, walk.deferred)
        assertEquals("android_sync: no marker failure", 0, walk.markerFailures)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertTrue("android_sync: nothing was enqueued for a live marker", enqueued.isEmpty())
        assertTrue("android_sync: the live marker was not cleared", cleared.isEmpty())
    }

    /** Case (i), second half: the row commits and the save-path enqueue fails; the current-token marker is still there. */
    @Test
    fun `after the row commits and enqueue fails the current-token marker is still there`() {
        val cleared = ArrayList<String>()
        val enqueued = ArrayList<Transcription>()
        var rowAbsent = true
        // First walk: the row is absent mid-save, the current-token marker is left and deferred.
        val first = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("live", THIS_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { null },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the mid-save marker is left and deferred", 1, first.deferred)
        assertTrue("android_sync: nothing was cleared mid-save", cleared.isEmpty())
        // The save commits (the row now exists) and the save-path enqueue fails: the marker stays.
        // The walk still never touches the current-token marker.
        rowAbsent = false
        val second = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("live", THIS_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { if (rowAbsent) null else aTranscription(it) },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the post-commit marker is still left and deferred", 1, second.deferred)
        assertEquals("android_sync: the walk never enqueues a current-token marker", 0, enqueued.size)
        assertTrue("android_sync: the marker is still there for the next start", cleared.isEmpty())
    }

    /** Case (ii): a previous run died between the marker and the save - old token, row absent -> discarded. */
    @Test
    fun `an old-token marker whose row is absent is discarded`() {
        val cleared = ArrayList<String>()
        val enqueued = ArrayList<Transcription>()
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("dead", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { null },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: an orphaned old-token marker is not re-queued", 0, walk.reQueued)
        assertEquals("android_sync: nothing is deferred for an old token", 0, walk.deferred)
        assertEquals("android_sync: no marker failure", 0, walk.markerFailures)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertTrue("android_sync: nothing was enqueued for an absent row", enqueued.isEmpty())
        assertEquals("android_sync: the discarded marker was cleared", listOf("dead"), cleared)
    }

    /** Case (iii): a previous run died between the save and the enqueue - old token, row present -> re-queued. */
    @Test
    fun `an old-token marker whose row is present is re-queued`() {
        val enqueued = ArrayList<Transcription>()
        val cleared = ArrayList<String>()
        val walk = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("stuck", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { id -> aTranscription(id) },
            enqueue = { enqueued.add(it) },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the old-token stuck save is re-queued once", 1, walk.reQueued)
        assertEquals("android_sync: nothing is deferred for an old token", 0, walk.deferred)
        assertEquals("android_sync: no enqueue failed", 0, walk.markerFailures)
        assertTrue("android_sync: the walk reached the end", walk.complete)
        assertEquals("android_sync: the row was enqueued", listOf("stuck"), enqueued.map { it.id })
        assertEquals("android_sync: the re-queued marker was cleared", listOf("stuck"), cleared)
    }
}
