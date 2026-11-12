package dev.breaker.dictation

import dev.breaker.dictation.history.PurgeReport
import dev.breaker.dictation.wiring.BackfillWalk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The tombstone sweep decision at start-up: it runs only when the row purge ran under its clock guard and the
 * backfill walk reached the end, never before the walk, and never when the walk failed.
 */
class StartUpSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 2_000_000_000_000L

    private val msPerDay = 24L * 60L * 60L * 1000L

    private val purged = PurgeOutcome.Purged(PurgeReport(purged = 0, tombstoned = 0, cutoff = now))

    private fun store() = PurgeBookkeepingStore(File(tmp.root, PurgeBookkeepingStore.FILE_NAME))

    @Test
    fun `a purged outcome and a complete walk run the sweep once`() {
        var swept = 0
        val ran = sweepAfterCompleteWalk(purged, BackfillWalk(queued = 3, complete = true)) { swept++ }
        assertTrue("android_app: the sweep ran", ran)
        assertEquals("android_app: exactly once", 1, swept)
    }

    @Test
    fun `a skipped outcome never sweeps`() {
        var swept = 0
        val ran = sweepAfterCompleteWalk(PurgeOutcome.Skipped, BackfillWalk(queued = 3, complete = true)) { swept++ }
        assertFalse("android_app: the clock guard skipped the purge, so no sweep", ran)
        assertEquals("android_app: the sweep never ran", 0, swept)
    }

    @Test
    fun `a walk cut at the page cap never sweeps`() {
        var swept = 0
        val ran = sweepAfterCompleteWalk(purged, BackfillWalk(queued = 1_000_000, complete = false)) { swept++ }
        assertFalse("android_app: a cut walk may have left tombstones unqueued, so no sweep", ran)
        assertEquals("android_app: the sweep never ran", 0, swept)
    }

    @Test
    fun `a walk that throws never reaches the sweep and the failure reaches the caller`() {
        var swept = false
        assertThrows(IllegalStateException::class.java) {
            backfillThenSweep(purged, walk = { throw IllegalStateException("fixture failure") }, sweep = { swept = true })
        }
        assertFalse("android_app: no sweep after a failed walk", swept)
    }

    @Test
    fun `the walk runs before the sweep`() {
        val log = mutableListOf<String>()
        val ran = backfillThenSweep(
            purged,
            walk = {
                log.add("walk")
                BackfillWalk(queued = 1, complete = true)
            },
            sweep = { log.add("sweep") },
        )
        assertTrue("android_app: the sweep ran", ran)
        assertEquals("android_app: the order of the two steps", listOf("walk", "sweep"), log)
    }

    @Test
    fun `a sweep that throws is not swallowed here so the app can catch it`() {
        assertThrows(IllegalStateException::class.java) {
            backfillThenSweep(
                purged,
                walk = { BackfillWalk(queued = 0, complete = true) },
                sweep = { throw IllegalStateException("fixture failure") },
            )
        }
    }

    @Test
    fun `the guard outcome reaches the backfill when the purge ran`() {
        var seen: PurgeOutcome? = null
        runStartUpAfterClear(
            clearFinished = false,
            syncOn = true,
            now = now,
            autoTimeOn = true,
            purgeStore = store(),
            purge = { PurgeReport(purged = 0, tombstoned = 0, cutoff = now) },
            backfill = { seen = it },
        )
        assertTrue("android_app: the backfill is told the purge ran", seen is PurgeOutcome.Purged)
    }

    @Test
    fun `a skipped purge hands the skipped outcome to the backfill`() {
        val skipStore = store()
        skipStore.recordLastPurgeAt(now - 40 * msPerDay)
        var seen: PurgeOutcome? = null
        runStartUpAfterClear(
            clearFinished = false,
            syncOn = true,
            now = now,
            autoTimeOn = false,
            purgeStore = skipStore,
            purge = { PurgeReport(purged = 0, tombstoned = 0, cutoff = now) },
            backfill = { seen = it },
        )
        assertTrue("android_app: the backfill is told the purge was skipped", seen is PurgeOutcome.Skipped)
    }

    @Test
    fun `sync off or a finished clear never calls the backfill so nothing sweeps`() {
        var calls = 0
        runStartUpAfterClear(
            clearFinished = false,
            syncOn = false,
            now = now,
            autoTimeOn = true,
            purgeStore = store(),
            purge = { PurgeReport(purged = 0, tombstoned = 0, cutoff = now) },
            backfill = { calls++ },
        )
        runStartUpAfterClear(
            clearFinished = true,
            syncOn = true,
            now = now,
            autoTimeOn = true,
            purgeStore = store(),
            purge = { PurgeReport(purged = 0, tombstoned = 0, cutoff = now) },
            backfill = { calls++ },
        )
        assertEquals("android_app: neither case reaches the backfill", 0, calls)
    }
}
