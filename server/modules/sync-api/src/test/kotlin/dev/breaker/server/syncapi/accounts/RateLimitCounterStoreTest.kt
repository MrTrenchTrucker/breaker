package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Tests for [RateLimitCounterStore] — the per-address fixed-window counter store. */
internal class RateLimitCounterStoreTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: RateLimitCounterStore

    @Before
    fun openStore() {
        temp = openTemp()
        store = RateLimitCounterStore(
            temp.db,
            Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
            maxRows = 5,
        )
    }

    // ---------- helpers ----------

    private fun countOf(route: String, address: String): Long =
        runBlocking { countOfAt(temp.db, route, address) }

    private suspend fun countOfAt(
        db: dev.breaker.server.syncapi.db.SqliteDatabase,
        route: String,
        address: String,
    ): Long =
        db.transaction<Long> { connection ->
            connection.prepareStatement(
                "SELECT count FROM rate_limit_counters WHERE route = ? AND address = ?",
            ).use { statement ->
                statement.setString(1, route)
                statement.setString(2, address)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getLong(1) else -1L
                }
            }
        }

    private fun windowStartOf(route: String, address: String): Long =
        runBlocking { windowStartOfAt(temp.db, route, address) }

    private suspend fun windowStartOfAt(
        db: dev.breaker.server.syncapi.db.SqliteDatabase,
        route: String,
        address: String,
    ): Long =
        db.transaction<Long> { connection ->
            connection.prepareStatement(
                "SELECT window_start_ms FROM rate_limit_counters WHERE route = ? AND address = ?",
            ).use { statement ->
                statement.setString(1, route)
                statement.setString(2, address)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getLong(1) else -1L
                }
            }
        }

    // ---------- happy path: counting ----------

    @Test
    fun `first request to an address is allowed and counted as 1`() = runBlocking {
        val result = store.check("register", "10.0.0.1", 10)
        assertEquals("first request allowed", RateLimitResult.Allowed, result)
        assertEquals("count 1 after first request", 1L, countOf("register", "10.0.0.1"))
        assertEquals("window starts at now (0)", 0L, windowStartOf("register", "10.0.0.1"))
    }

    @Test
    fun `requests under the cap are allowed and increment`() = runBlocking {
        repeat(10) { assertEquals("request $it allowed", RateLimitResult.Allowed, store.check("register", "10.0.0.1", 10)) }
        assertEquals("count 10 after 10 requests", 10L, countOf("register", "10.0.0.1"))
    }

    @Test
    fun `the 11th request over a cap of 10 is refused`() = runBlocking {
        repeat(10) { store.check("register", "10.0.0.1", 10) }
        val refused = store.check("register", "10.0.0.1", 10) as RateLimitResult.Refused
        assertTrue("retryAfterSeconds is at least 1", refused.retryAfterSeconds >= 1)
        assertTrue("retryAfterSeconds is at most 60", refused.retryAfterSeconds <= 60)
        assertEquals("count unchanged by the refusal", 10L, countOf("register", "10.0.0.1"))
    }

    // ---------- per-address isolation ----------

    @Test
    fun `a different address gets its own counter`() = runBlocking {
        repeat(10) { store.check("register", "10.0.0.1", 10) }
        assertEquals("10.0.0.1 refused at cap 10", RateLimitResult.Refused(60), store.check("register", "10.0.0.1", 10))
        assertEquals("10.0.0.2 allowed at cap 10", RateLimitResult.Allowed, store.check("register", "10.0.0.2", 10))
        assertEquals("10.0.0.1 count stays 10", 10L, countOf("register", "10.0.0.1"))
        assertEquals("10.0.0.2 count is 1", 1L, countOf("register", "10.0.0.2"))
    }

    // ---------- global backstop ----------

    @Test
    fun `a global row is a separate key of its own`() = runBlocking {
        repeat(120) { assertEquals("global request $it allowed", RateLimitResult.Allowed, store.check("register", RateLimitCounterStore.GLOBAL_ADDRESS, 120)) }
        val refused = store.check("register", RateLimitCounterStore.GLOBAL_ADDRESS, 120) as RateLimitResult.Refused
        assertTrue("global refusal has retryAfterSeconds >= 1", refused.retryAfterSeconds >= 1)
        assertEquals("global count stays 120", 120L, countOf("register", RateLimitCounterStore.GLOBAL_ADDRESS))
    }

    // ---------- window expiry ----------

    @Test
    fun `window expires after WINDOW_MS and restarts at 1`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)
        val s = RateLimitCounterStore(temp.db, clock, maxRows = 5)
        repeat(10) { s.check("register", "10.0.0.1", 10) }
        assertEquals("count 10 before window close", 10L, countOf("register", "10.0.0.1"))
        // Advance past the window: 60_000 ms later is a new window.
        val s2 = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(60_000), ZoneOffset.UTC), maxRows = 5)
        assertEquals("new window count restarts at 1", RateLimitResult.Allowed, s2.check("register", "10.0.0.1", 10))
        assertEquals("count 1 after window expiry", 1L, countOf("register", "10.0.0.1"))
        assertEquals("window_start_ms moved to 60000", 60_000L, windowStartOf("register", "10.0.0.1"))
    }

    @Test
    fun `a request one millisecond before window end is still in the old window`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)
        val s = RateLimitCounterStore(temp.db, clock, maxRows = 5)
        repeat(10) { s.check("register", "10.0.0.1", 10) }
        val s2 = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(59_999), ZoneOffset.UTC), maxRows = 5)
        val refused = s2.check("register", "10.0.0.1", 10) as RateLimitResult.Refused
        assertEquals("count stays 10 at 59999 ms", 10L, countOf("register", "10.0.0.1"))
        assertTrue("refused with retryAfterSeconds >= 1", refused.retryAfterSeconds >= 1)
    }

    // ---------- full table ----------

    @Test
    fun `a full table answers without counting and never refuses a new address`() = runBlocking {
        // Fill the table with 5 live rows (single-key checks: one row each).
        for (i in 1..5) { store.check("register", "10.0.0.$i", 10) }
        // The 6th address gets no row inserted and is answered Allowed (never refused).
        assertEquals("new address on a full table is Allowed", RateLimitResult.Allowed, store.check("register", "10.0.0.6", 10))
        assertEquals("table still has 5 rows", 5L, runBlocking {
            temp.db.transaction<Long> { connection ->
                connection.prepareStatement("SELECT count(*) FROM rate_limit_counters").use { statement ->
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getLong(1) else 0L
                    }
                }
            }
        })
    }

    // ---------- the pair: per-address and global in one transaction ----------

    private fun rowCount(): Long = runBlocking {
        temp.db.transaction<Long> { connection ->
            connection.prepareStatement("SELECT count(*) FROM rate_limit_counters").use { statement ->
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
        }
    }

    @Test
    fun `a pair counts both the address and the global row`() = runBlocking {
        assertEquals("allowed", RateLimitResult.Allowed, store.checkPair("register", "10.0.0.1", 10, 120))
        assertEquals("address row", 1L, countOf("register", "10.0.0.1"))
        assertEquals("global row", 1L, countOf("register", RateLimitCounterStore.GLOBAL_ADDRESS))
    }

    @Test
    fun `a pair refused by the global cap counts neither row`() = runBlocking {
        assertEquals("first", RateLimitResult.Allowed, store.checkPair("register", "10.0.0.1", 10, 2))
        assertEquals("second", RateLimitResult.Allowed, store.checkPair("register", "10.0.0.2", 10, 2))
        val answer = store.checkPair("register", "10.0.0.3", 10, 2)
        assertTrue("the third is refused by the global cap: $answer", answer is RateLimitResult.Refused)
        assertEquals("global stays 2", 2L, countOf("register", RateLimitCounterStore.GLOBAL_ADDRESS))
        assertEquals("the refused address got no row", -1L, countOf("register", "10.0.0.3"))
    }

    @Test
    fun `a pair refused by the address cap leaves the global row unchanged`() = runBlocking {
        repeat(2) { store.checkPair("register", "10.0.0.1", 2, 120) }
        val answer = store.checkPair("register", "10.0.0.1", 2, 120)
        assertTrue("refused by the address cap: $answer", answer is RateLimitResult.Refused)
        assertEquals("address stays 2", 2L, countOf("register", "10.0.0.1"))
        assertEquals("global stays 2", 2L, countOf("register", RateLimitCounterStore.GLOBAL_ADDRESS))
    }

    @Test
    fun `the same address on two routes has two separate rows`() = runBlocking {
        store.check("register", "10.0.0.1", 10)
        store.check("salt", "10.0.0.1", 60)
        assertEquals("register row", 1L, countOf("register", "10.0.0.1"))
        assertEquals("salt row", 1L, countOf("salt", "10.0.0.1"))
        assertEquals("two rows", 2L, rowCount())
    }

    @Test
    fun `the sweep deletes a window that has expired and keeps a live one`() = runBlocking {
        store.check("register", "10.0.0.1", 10)
        val later = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(60_000), ZoneOffset.UTC), maxRows = 5)
        later.check("register", "10.0.0.2", 10)
        later.deleteStale()
        assertEquals("the expired window is gone", -1L, countOf("register", "10.0.0.1"))
        assertEquals("the live window stays", 1L, countOf("register", "10.0.0.2"))
    }


    @Test
    fun `a full table answers a new address without counting it and an existing address still counts`() = runBlocking {
        val small = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC), maxRows = 3)
        assertEquals("a", RateLimitResult.Allowed, small.checkPair("register", "a", 10, 120))
        assertEquals("b", RateLimitResult.Allowed, small.checkPair("register", "b", 10, 120))
        assertEquals("the table is full", 3L, rowCount())
        repeat(20) { assertEquals("c passes uncounted $it", RateLimitResult.Allowed, small.checkPair("register", "c", 10, 120)) }
        assertEquals("c has no row", -1L, countOf("register", "c"))
        assertEquals("the table did not grow", 3L, rowCount())
        assertEquals("a is counted again", RateLimitResult.Allowed, small.checkPair("register", "a", 10, 120))
        assertEquals("a counts 2", 2L, countOf("register", "a"))
    }
}
