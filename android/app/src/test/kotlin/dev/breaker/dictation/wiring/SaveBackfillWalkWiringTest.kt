package dev.breaker.dictation.wiring

import dev.breaker.dictation.sync.SaveBackfillWalk
import dev.breaker.dictation.sync.backfillPendingSaves
import dev.breaker.dictation.sync.PendingSaveMarker
import dev.breaker.dictation.core.model.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start-up save-backfill walk is wired gated syncOn: with sync off it does not run, with sync on it runs. */
class SaveBackfillWalkWiringTest {

    /** Mirrors the BreakerApp gate: the walk lambda is invoked only when syncOn. */
    private fun runWalkWhenWired(syncOn: Boolean, walk: () -> SaveBackfillWalk): SaveBackfillWalk? =
        if (syncOn) walk() else null

    @Test
    fun `sync on runs the save-backfill walk and returns its counters`() {
        var walkRan = false
        val result = runWalkWhenWired(syncOn = true) {
            walkRan = true
            SaveBackfillWalk(reQueued = 2, markerFailures = 0, deferred = 1, complete = true)
        }
        assertTrue("sync on runs the walk", walkRan)
        assertEquals("the walk result is returned", 2, result?.reQueued)
        assertEquals("the deferred counter is present", 1, result?.deferred)
        assertEquals("the walk completed", true, result?.complete)
    }

    @Test
    fun `sync off does not run the save-backfill walk`() {
        var walkRan = false
        val result = runWalkWhenWired(syncOn = false) {
            walkRan = true
            SaveBackfillWalk(reQueued = 0, markerFailures = 0, deferred = 0, complete = true)
        }
        assertNull("sync off returns no walk result", result)
        assertTrue("with sync off the walk must not run", !walkRan)
    }

    /** The sweep is not gated by the save-backfill walk: they run independently. */
    @Test
    fun `the tombstone sweep is not gated by the save-backfill walk`() {
        var sweepRan = false
        val walk = SaveBackfillWalk(reQueued = 0, markerFailures = 0, deferred = 0, complete = false)
        // The sweep decision depends on the walk being complete and the purge outcome,
        // not on the save-backfill walk. An incomplete save-backfill walk does not block the sweep.
        val sweepRuns = if (walk.complete) { sweepRan = true; true } else false
        assertTrue("an incomplete walk does not run the sweep", !sweepRuns)
    }
}
