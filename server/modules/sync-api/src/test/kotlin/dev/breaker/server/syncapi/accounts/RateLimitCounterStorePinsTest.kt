package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Pins for peekOverCap (read-only cap check) and countFailure (counts one failure, never checks caps). */
internal class RateLimitCounterStorePinsTest : TempDatabaseTest() {

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

    private fun countOf(route: String, address: String): Long = runBlocking {
        temp.db.transaction<Long> { connection ->
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
    }

    // ---------- peekOverCap boundary: 9 / 10 ----------

    @Test
    fun `nine counted failures peek as allowed, the tenth peek refuses and counts nothing`() = runBlocking {
        repeat(9) { store.countFailure("complete-reset", "10.0.0.1") }
        assertNull("peek at 9 is allowed", store.peekOverCap("complete-reset", "10.0.0.1", 10, 120))
        assertEquals("count still 9 after peeking", 9L, countOf("complete-reset", "10.0.0.1"))
        store.countFailure("complete-reset", "10.0.0.1")
        val refused = store.peekOverCap("complete-reset", "10.0.0.1", 10, 120) as RateLimitResult.Refused
        assertTrue("retryAfterSeconds in 1..60: ${refused.retryAfterSeconds}", refused.retryAfterSeconds in 1..60)
        assertEquals("count stayed 10 after refused peek", 10L, countOf("complete-reset", "10.0.0.1"))
    }

    @Test
    fun `peek on a fresh address answers null and inserts no row`() = runBlocking {
        assertNull("fresh address peeks null", store.peekOverCap("complete-reset", "10.0.0.1", 10, 120))
        assertEquals("no row was inserted", -1L, countOf("complete-reset", "10.0.0.1"))
    }

    // ---------- peekOverCap is read only: never increments ----------

    @Test
    fun `peekOverCap never increments any counter, address or global`() = runBlocking {
        repeat(3) { store.countFailure("complete-reset", "10.0.0.1") }
        repeat(5) { store.peekOverCap("complete-reset", "10.0.0.1", 10, 120) }
        assertEquals("address count is still 3", 3L, countOf("complete-reset", "10.0.0.1"))
        assertEquals("global row is still 3 after peeks", 3L, countOf("complete-reset", RateLimitCounterStore.GLOBAL_ADDRESS))
    }

    // ---------- window roll: 59_999 vs 60_000 ----------

    @Test
    fun `the window expires at 60000 ms and the next counted failure restarts at 1`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)
        val s = RateLimitCounterStore(temp.db, clock, maxRows = 5)
        repeat(10) { s.countFailure("complete-reset", "10.0.0.1") }
        assertEquals("count 10 before window close", 10L, countOf("complete-reset", "10.0.0.1"))
        val s2 = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(60_000), ZoneOffset.UTC), maxRows = 5)
        assertNull("new window peek is null", s2.peekOverCap("complete-reset", "10.0.0.1", 10, 120))
        assertEquals("a peek writes nothing, the old row is still there", 10L, countOf("complete-reset", "10.0.0.1"))
        s2.countFailure("complete-reset", "10.0.0.1")
        assertEquals("count restarts at 1 after window expiry", 1L, countOf("complete-reset", "10.0.0.1"))
    }

    @Test
    fun `a peek one millisecond before window end is still refused`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)
        val s = RateLimitCounterStore(temp.db, clock, maxRows = 5)
        repeat(10) { s.countFailure("complete-reset", "10.0.0.1") }
        val s2 = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(59_999), ZoneOffset.UTC), maxRows = 5)
        val refused = s2.peekOverCap("complete-reset", "10.0.0.1", 10, 120) as RateLimitResult.Refused
        assertTrue("refused with retryAfterSeconds >= 1: ${refused.retryAfterSeconds}", refused.retryAfterSeconds >= 1)
        assertEquals("count stays 10 at 59999 ms", 10L, countOf("complete-reset", "10.0.0.1"))
    }

    // ---------- countFailure counts address + global, never refuses ----------

    @Test
    fun `countFailure increments both the address row and the global backstop row`() = runBlocking {
        store.countFailure("complete-reset", "10.0.0.1")
        store.countFailure("complete-reset", "10.0.0.2")
        assertEquals("address row 1", 1L, countOf("complete-reset", "10.0.0.1"))
        assertEquals("address row 2", 1L, countOf("complete-reset", "10.0.0.2"))
        assertEquals("global row is 2", 2L, countOf("complete-reset", RateLimitCounterStore.GLOBAL_ADDRESS))
    }

    @Test
    fun `countFailure on an expired window restarts at 1`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)
        val s = RateLimitCounterStore(temp.db, clock, maxRows = 5)
        s.countFailure("complete-reset", "10.0.0.1")
        val s2 = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(60_000), ZoneOffset.UTC), maxRows = 5)
        s2.countFailure("complete-reset", "10.0.0.1")
        assertEquals("count 1 after expiry", 1L, countOf("complete-reset", "10.0.0.1"))
    }

    // ---------- full table: peek answers without counting, never refuses ----------

    @Test
    fun `a full table lets peek answer without counting a new key`() = runBlocking {
        for (i in 1..5) { store.countFailure("complete-reset", "10.0.0.$i") }
        assertNull("new address on a full table peeks null", store.peekOverCap("complete-reset", "10.0.0.6", 10, 120))
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
}
