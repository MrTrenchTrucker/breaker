package dev.breaker.dictation

import dev.breaker.dictation.history.PurgeReport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The start-up sequence after an interrupted sign-out clear, driven through its ports.
 *
 * The wiring in BreakerApp is untestable (it is an Android Application), so the sequence lives in
 * runStartUpAfterClear and is driven here: the guard decides the purge, and the backfill gate holds.
 */
class StartUpSequenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val MS_PER_DAY = 24L * 60L * 60L * 1000L

    private fun store() = PurgeBookkeepingStore(File(tmp.root, PurgeBookkeepingStore.FILE_NAME))

    private class PurgeFake {
        var calls = 0
        fun run(now: Long): PurgeReport {
            calls++
            return PurgeReport(purged = 0, tombstoned = 0, cutoff = now)
        }
    }

    /** APP_R2_B1: the guard decides the purge; a removed guard call purges unconditionally and goes red here. */
    @Test
    fun `the guard decides the purge and the backfill gate holds`() {
        // auto time on -> GO -> the purge runs and the backfill runs.
        val goStore = store()
        val goPurge = PurgeFake()
        var backfilled = false
        val goOutcome = runStartUpAfterClear(
            clearFinished = false,
            syncOn = true,
            now = 2_000_000_000_000L,
            autoTimeOn = true,
            purgeStore = goStore,
            purge = { goPurge.run(2_000_000_000_000L) },
            backfill = { backfilled = true },
        )
        assertTrue("android_app: auto time on purges", goOutcome is PurgeOutcome.Purged)
        assertEquals("android_app: the guard ran the purge once", 1, goPurge.calls)
        assertTrue("android_app: sync on and no clear means the backfill runs", backfilled)

        // auto time off + a forward jump -> DEFERRED_SKEW -> the purge does NOT run.
        val skipStore = store()
        skipStore.recordLastPurgeAt(2_000_000_000_000L - 40 * MS_PER_DAY)
        val skipPurge = PurgeFake()
        val skipOutcome = runStartUpAfterClear(
            clearFinished = false,
            syncOn = true,
            now = 2_000_000_000_000L,
            autoTimeOn = false,
            purgeStore = skipStore,
            purge = { skipPurge.run(2_000_000_000_000L) },
            backfill = { },
        )
        assertTrue("android_app: a suspected jump skips the purge", skipOutcome is PurgeOutcome.Skipped)
        assertEquals("android_app: the skipped purge never ran", 0, skipPurge.calls)

        // a finished clear -> the backfill does NOT run even with sync on.
        val clearStore = store()
        var backfilledAfterClear = false
        runStartUpAfterClear(
            clearFinished = true,
            syncOn = true,
            now = 2_000_000_000_000L,
            autoTimeOn = true,
            purgeStore = clearStore,
            purge = { PurgeFake().run(2_000_000_000_000L) },
            backfill = { backfilledAfterClear = true },
        )
        assertFalse("android_app: a finished clear skips the backfill", backfilledAfterClear)

        // sync off -> the backfill does NOT run.
        val offStore = store()
        var backfilledSyncOff = false
        runStartUpAfterClear(
            clearFinished = false,
            syncOn = false,
            now = 2_000_000_000_000L,
            autoTimeOn = true,
            purgeStore = offStore,
            purge = { PurgeFake().run(2_000_000_000_000L) },
            backfill = { backfilledSyncOff = true },
        )
        assertFalse("android_app: sync off means no backfill", backfilledSyncOff)
    }
}
