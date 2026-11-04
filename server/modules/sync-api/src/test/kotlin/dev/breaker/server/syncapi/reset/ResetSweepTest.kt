package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.runHandoffSweep
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expired reset codes are deleted by deleteExpired and by the hand-off sweep loop, also at boot. */
internal class ResetSweepTest : ResetStoreTestBase() {

    private fun codeRows(id: Long): Long = num("SELECT count(*) FROM reset_codes WHERE account_id = $id")

    /** Runs the sweep loop with the reset store until its first pause, which ends it. Returns the pauses asked for. */
    private fun sweepUntilFirstPause(): List<Duration> {
        val handoff = HandoffFixtures.handoffStore(temp, tokens, clock)
        val paused = ArrayList<Duration>()
        runBlocking(SupervisorJob()) {
            val child = launch {
                runHandoffSweep(
                    handoff,
                    store,
                    pause = { duration ->
                        paused.add(duration)
                        throw CancellationException("test")
                    },
                )
            }
            try {
                child.join()
            } catch (_: CancellationException) {
                // expected: the loop ends
            }
        }
        return paused
    }

    @Test
    fun `deleteExpired removes a code dead at its instant and an older one and keeps one a millisecond younger`() {
        val now = clock.millis()
        plantCode(alice, 1, now)
        plantCode(bob, 2, now + 1L)
        plantCode(carol, 3, now - 5_000L)

        assertEquals("sync-api: deleteExpired returns the count of deleted rows", 2, runBlocking { store.deleteExpired() })

        assertEquals("sync-api: the code dead at its instant", 0L, codeRows(alice))
        assertEquals("sync-api: the code a millisecond younger", 1L, codeRows(bob))
        assertEquals("sync-api: the older code", 0L, codeRows(carol))
    }

    @Test
    fun `runHandoffSweep with a reset store deletes an expired code at the first pass before the first pause`() {
        plantCode(alice, 1, clock.millis() - 1L)
        plantCode(bob, 2, clock.millis() + 3_600_000L)

        val paused = sweepUntilFirstPause()

        assertEquals("sync-api: the expired code is gone after the first pass", 0L, codeRows(alice))
        assertEquals("sync-api: the live code stays", 1L, codeRows(bob))
        assertEquals("sync-api: the pause was asked for once", listOf(Duration.ofSeconds(60)), paused)
    }

    @Test
    fun `a failing hand-off delete does not stop the reset delete`() {
        plantCode(alice, 1, clock.millis() - 1L)
        runSql(temp, "DROP TABLE signin_handoff")

        val paused = sweepUntilFirstPause()

        assertEquals("sync-api: the expired code is gone although the hand-off delete failed", 0L, codeRows(alice))
        assertEquals("sync-api: the pause was asked for once", 1, paused.size)
    }
}
