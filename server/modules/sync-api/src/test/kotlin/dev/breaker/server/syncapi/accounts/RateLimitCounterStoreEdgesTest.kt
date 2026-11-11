package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The documented numbers of the counter store, the round-up of the wait, and the sweep that deletes stale rows. */
internal class RateLimitCounterStoreEdgesTest : TempDatabaseTest() {

    private class StepClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }

    private lateinit var temp: TempDatabase
    private val clock = StepClock(0)
    private lateinit var store: RateLimitCounterStore

    @Before
    fun openStore() {
        temp = openTemp()
        store = RateLimitCounterStore(temp.db, clock)
    }

    private fun rowCount(): Long = runBlocking {
        temp.db.transaction<Long> { connection ->
            connection.prepareStatement("SELECT count(*) FROM rate_limit_counters").use { statement ->
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else -1L }
            }
        }
    }

    @Test
    fun `the documented numbers are pinned`() {
        assertEquals("sync-api: row cap", 10_000, RateLimitCounterStore.DEFAULT_MAX_ROWS)
        assertEquals("sync-api: window", 60_000L, RateLimitCounterStore.WINDOW_MS)
        assertEquals("sync-api: global key", "*", RateLimitCounterStore.GLOBAL_ADDRESS)
    }

    @Test
    fun `the global key can never be a real peer address`() {
        val key = RateLimitCounterStore.GLOBAL_ADDRESS
        assertTrue(
            "sync-api: an IP literal holds only digits, letters, dots and colons",
            key.none { it.isLetterOrDigit() || it == '.' || it == ':' },
        )
    }

    @Test
    fun `the wait is rounded up to whole seconds and is never zero`() {
        runBlocking {
            assertEquals("sync-api: the first request counts", RateLimitResult.Allowed, store.check("r", "a", 1))
            clock.nowMs = 58_500
            assertEquals("sync-api: 1500 ms left", RateLimitResult.Refused(2), store.check("r", "a", 1))
            clock.nowMs = 59_000
            assertEquals("sync-api: 1000 ms left", RateLimitResult.Refused(1), store.check("r", "a", 1))
            clock.nowMs = 59_999
            assertEquals("sync-api: 1 ms left", RateLimitResult.Refused(1), store.check("r", "a", 1))
        }
    }

    @Test
    fun `the sweep deletes a stale row and a cancelled pause ends it`() {
        runBlocking {
            store.check("r", "a", 5)
            assertEquals("sync-api: one live row", 1L, rowCount())
            clock.nowMs = 120_000
            var stopped = false
            try {
                runRateLimitCounterSweep(store, Duration.ofMinutes(1)) { throw CancellationException("stop") }
            } catch (cancellation: CancellationException) {
                stopped = true
            }
            assertTrue("sync-api: cancellation ends the sweep", stopped)
        }
        assertEquals("sync-api: the stale row is gone", 0L, rowCount())
    }

    @Test
    fun `the sweep keeps a row whose window is still live`() {
        runBlocking {
            store.check("r", "a", 5)
            clock.nowMs = 30_000
            try {
                runRateLimitCounterSweep(store, Duration.ofMinutes(1)) { throw CancellationException("stop") }
            } catch (cancellation: CancellationException) {
                // the sweep ran once and the pause ended it
            }
        }
        assertEquals("sync-api: the live row stays", 1L, rowCount())
    }
}
