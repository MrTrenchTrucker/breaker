package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * deleteStale deletes the rows the attempt-time rule already treats as no row: an expired lock, and
 * a partial count with no wrong try for more than 15 minutes. The edges are pinned with literals.
 */
internal class SigninAttemptStoreSweepTest : TempDatabaseTest() {

    private val nowMs = AccountFixtures.FIXED_MILLIS
    private lateinit var temp: TempDatabase
    private lateinit var store: SigninAttemptStore

    @Before
    fun setUp() {
        temp = openTemp()
        store = SigninAttemptStore(temp.db, MutableClock(Instant.ofEpochMilli(nowMs)))
    }

    private fun insertRow(folded: String, wrongCount: Long, lockedUntilMs: Long, lastAttemptMs: Long) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, folded)
                    statement.setLong(2, wrongCount)
                    statement.setLong(3, lockedUntilMs)
                    statement.setLong(4, lastAttemptMs)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun deleteStale(): Int = runBlocking { store.deleteStale() }

    private fun attemptOf(folded: String): SigninAttemptStore.AttemptInfo? =
        runBlocking { store.attemptOf(folded) }

    @Test
    fun `a partial count exactly 15 minutes old is kept`() {
        insertRow("alice", 4L, 0L, nowMs - 900_000L)

        assertEquals("nothing deleted", 0, deleteStale())
        assertNotNull("the row stays", attemptOf("alice"))
    }

    @Test
    fun `a partial count 15 minutes and one millisecond old is deleted`() {
        insertRow("alice", 4L, 0L, nowMs - 900_001L)

        assertEquals("one row deleted", 1, deleteStale())
        assertNull("the row is gone", attemptOf("alice"))
    }

    @Test
    fun `a young partial count is kept`() {
        insertRow("alice", 2L, 0L, nowMs - 60_000L)

        assertEquals("nothing deleted", 0, deleteStale())
        assertNotNull("the row stays", attemptOf("alice"))
    }

    @Test
    fun `a lock that ends exactly now is deleted`() {
        insertRow("alice", 5L, nowMs, nowMs - 900_000L)

        assertEquals("one row deleted", 1, deleteStale())
        assertNull("the row is gone", attemptOf("alice"))
    }

    @Test
    fun `a lock that ends one millisecond from now is kept`() {
        insertRow("alice", 5L, nowMs + 1L, nowMs - 899_999L)

        assertEquals("nothing deleted", 0, deleteStale())
        assertNotNull("the row stays", attemptOf("alice"))
    }

    @Test
    fun `an expired lock is deleted even when its last attempt is recent`() {
        insertRow("alice", 5L, nowMs - 1_000L, nowMs - 2_000L)

        assertEquals("one row deleted", 1, deleteStale())
        assertNull("the row is gone", attemptOf("alice"))
    }

    @Test
    fun `a live lock whose last attempt is older than 15 minutes is kept`() {
        // Not reachable through the counting code, but the lock decides, never the age.
        insertRow("alice", 5L, nowMs + 600_000L, nowMs - 1_000_000L)

        assertEquals("nothing deleted", 0, deleteStale())
        assertNotNull("the row stays", attemptOf("alice"))
    }

    @Test
    fun `an empty table deletes nothing`() {
        assertEquals("nothing deleted", 0, deleteStale())
    }

    @Test
    fun `rows are judged one by one and the count is returned`() {
        insertRow("old1", 3L, 0L, nowMs - 2_000_000L)
        insertRow("old2", 5L, nowMs - 5_000L, nowMs - 905_000L)
        insertRow("live", 5L, nowMs + 300_000L, nowMs - 600_000L)

        assertEquals("two rows deleted", 2, deleteStale())
        assertNull("old1 is gone", attemptOf("old1"))
        assertNull("old2 is gone", attemptOf("old2"))
        assertNotNull("live stays", attemptOf("live"))
    }
}
