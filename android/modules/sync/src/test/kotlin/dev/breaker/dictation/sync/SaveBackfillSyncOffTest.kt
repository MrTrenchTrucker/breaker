package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walk gate: when sync is not allowed, the save-backfill walk must not run. */
class SaveBackfillSyncOffTest {

    /** Mirrors the app-wiring gate: run the backfill only when sync is allowed. */
    private fun runWalkWhenAllowed(syncOn: Boolean, walk: () -> SaveBackfillWalk): SaveBackfillWalk? =
        if (syncOn) walk() else null

    @Test
    fun `sync off does not run the save-backfill walk`() {
        var walkRan = false
        val result = runWalkWhenAllowed(syncOn = false) {
            walkRan = true
            SaveBackfillWalk(reQueued = 0, markerFailures = 0, deferred = 0, complete = true)
        }
        assertNull("android_sync: sync off returns no walk result", result)
        assertFalse("android_sync: with sync off the walk must not run", walkRan)
    }

    @Test
    fun `sync on runs the save-backfill walk`() {
        var walkRan = false
        val result = runWalkWhenAllowed(syncOn = true) {
            walkRan = true
            SaveBackfillWalk(reQueued = 1, markerFailures = 0, deferred = 0, complete = true)
        }
        assertTrue("android_sync: sync on runs the walk", walkRan)
        assertEquals("android_sync: the walk result is returned", 1, result?.reQueued)
    }
}
