package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deletes expired sign-in codes: one sweep at boot, then every interval. */
internal class HandoffSweepTest : TempDatabaseTest() {

    private val clock = MutableClock(HandoffFixtures.START)

    private class World(
        val temp: TempDatabase,
        val tokenStore: TokenStore,
        val store: HandoffStore,
        val accountId: Long,
    )

    private fun world(): World {
        val temp = openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val store = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        val account = TokenFixtures.register(temp, "alice", 1)
        return World(temp, tokenStore, store, account.accountId)
    }

    private fun insertExpiredRow(w: World, hash: ByteArray, expiresAtMs: Long) {
        HandoffFixtures.insertRawRow(w.temp, hash, w.accountId, 0L, expiresAtMs)
    }

    private fun hashOf(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

    @Test
    fun `deleteExpired removes a row dead at its expiry and keeps one a millisecond younger`() {
        val w = world()
        val nowMs = HandoffFixtures.START.toEpochMilli()
        insertExpiredRow(w, hashOf(1), nowMs)        // dead AT the instant
        insertExpiredRow(w, hashOf(2), nowMs + 1L)   // live one millisecond after

        val deleted = runBlocking { w.store.deleteExpired() }

        assertEquals("sync-api: deleteExpired returns the count of deleted rows", 1, deleted)
        assertEquals("sync-api: only the live row remains", 1L, HandoffFixtures.rowCountFor(w.temp, w.accountId))
    }

    @Test
    fun `deleteExpired never touches another table`() {
        val w = world()
        val nowMs = HandoffFixtures.START.toEpochMilli()
        insertExpiredRow(w, hashOf(1), nowMs)
        val token = TokenFixtures.issuedOf(runBlocking { w.tokenStore.issueSession(w.accountId) })
        // Expire the session row by its own expiry so a stray DELETE on tokens would reach it.
        runBlocking {
            w.temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE tokens SET expires_at_ms = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, nowMs)
                    statement.setLong(2, token.tokenId)
                    statement.executeUpdate()
                }
            }
        }

        runBlocking { w.store.deleteExpired() }

        assertEquals("sync-api: the handoff row is gone", 0L, HandoffFixtures.rowCountFor(w.temp, w.accountId))
        val tokenCount = runBlocking {
            w.temp.db.transaction { connection ->
                connection.prepareStatement("SELECT count(*) FROM tokens WHERE id = ?").use { statement ->
                    statement.setLong(1, token.tokenId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getLong(1) else 0L
                    }
                }
            }
        }
        assertEquals("sync-api: the expired session row still exists (deleteExpired does not touch tokens)", 1L, tokenCount)
    }

    @Test
    fun `the sweep interval constant is 60 seconds`() {
        assertEquals("sync-api: HANDOFF_SWEEP_INTERVAL must be 60 seconds", Duration.ofSeconds(60), HANDOFF_SWEEP_INTERVAL)
    }

    @Test
    fun `runHandoffSweep first sweep runs before the first pause and passes the 60 second interval`() {
        val w = world()
        val nowMs = HandoffFixtures.START.toEpochMilli()
        insertExpiredRow(w, hashOf(1), nowMs)  // already expired

        val paused = ArrayList<Duration>()
        runBlocking(SupervisorJob()) {
            val child = launch {
                runHandoffSweep(
                    w.store,
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

        assertEquals("sync-api: the expired row is gone after the first sweep", 0L, HandoffFixtures.rowCountFor(w.temp, w.accountId))
        assertEquals("sync-api: the pause was called once", 1, paused.size)
        assertEquals("sync-api: the interval passed to pause is 60 seconds", Duration.ofSeconds(60), paused[0])
    }

    @Test
    fun `runHandoffSweep a CancellationException from pause ends the loop`() {
        val w = world()
        val paused = ArrayList<Duration>()
        runBlocking(SupervisorJob()) {
            val child = launch {
                runHandoffSweep(
                    w.store,
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

        assertTrue("sync-api: the loop ended", paused.isNotEmpty())
    }

    @Test
    fun `runHandoffSweep a failing sweep over a closed database is swallowed and pause is still called`() {
        val w = world()
        w.temp.closeKeepingFiles()

        val paused = ArrayList<Duration>()
        runBlocking(SupervisorJob()) {
            val child = launch {
                runHandoffSweep(
                    w.store,
                    pause = { duration ->
                        paused.add(duration)
                        throw CancellationException("test")
                    },
                )
            }
            try {
                child.join()
            } catch (_: CancellationException) {
                // expected
            }
        }

        assertEquals("sync-api: the loop called pause after swallowing the failure", 1, paused.size)
    }
}
