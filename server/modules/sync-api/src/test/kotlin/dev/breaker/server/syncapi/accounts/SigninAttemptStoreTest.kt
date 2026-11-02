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
import java.time.ZoneId
import java.time.ZoneOffset

internal class SigninAttemptStoreTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    private val start = Instant.ofEpochMilli(AccountFixtures.FIXED_MILLIS)

    @Before
    fun openStore() {
        temp = openTemp()
        store = AccountFixtures.newStore(
            temp,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            Clock.fixed(start, ZoneOffset.UTC),
        )
    }

    private fun register(name: String, seed: Int) =
        AccountFixtures.registered(runBlocking { store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed)) })

    private fun attemptOf(foldedName: String): SigninAttemptStore.AttemptInfo? =
        runBlocking { store.attempts.attemptOf(foldedName) }

    private fun wrongCount(foldedName: String): Long =
        attemptOf(foldedName)?.wrongCount ?: -1L

    private fun lockedUntil(foldedName: String): Long =
        attemptOf(foldedName)?.lockedUntilMs ?: -1L

    @Test
    fun `fourth wrong try reaches count 4 with no lock`() {
        register("alice", 1)
        repeat(4) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count after 4 wrong tries", 4L, wrongCount("alice"))
        assertEquals("alice not locked after 4 wrong tries", 0L, lockedUntil("alice"))
    }

    @Test
    fun `fifth wrong try reaches count 5 and locks until now + 15 minutes`() {
        register("alice", 1)
        runBlocking { store.attempts.recordWrong("alice") }
        repeat(4) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count after 5 wrong tries", 5L, wrongCount("alice"))
        val locked = lockedUntil("alice")
        assertTrue("alice locked_until_ms is in the future", locked > start.toEpochMilli())
        assertTrue(
            "alice locked_until_ms is now + 15 minutes",
            locked == start.toEpochMilli() + 15L * 60 * 1000,
        )
    }

    @Test
    fun `a try inside a lock changes nothing`() {
        register("alice", 1)
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        val lockedBefore = lockedUntil("alice")
        val countBefore = wrongCount("alice")
        val lastAttemptBefore = attemptOf("alice")!!.lastAttemptMs
        val clock = mutableClock(start.plusSeconds(1 * 60L))
        val store2 = AccountStore(temp.db, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        runBlocking { store2.attempts.recordWrong("alice") }
        assertEquals("count unchanged inside the lock", countBefore, wrongCount("alice"))
        assertEquals("locked_until unchanged inside the lock", lockedBefore, lockedUntil("alice"))
        assertEquals("last_attempt_ms unchanged inside the lock", lastAttemptBefore, attemptOf("alice")!!.lastAttemptMs)
    }

    @Test
    fun `a wrong try at exactly the lock expiry instant restarts at count 1`() {
        register("alice", 1)
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count is 5", 5L, wrongCount("alice"))
        val locked = lockedUntil("alice")
        // Now exactly at locked_until_ms: the lock has expired, count restarts at 1.
        val clock = mutableClock(Instant.ofEpochMilli(locked))
        val store2 = AccountStore(temp.db, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        runBlocking { store2.attempts.recordWrong("alice") }
        assertEquals("count restarts at 1 at lock expiry instant", 1L, wrongCount("alice"))
        assertEquals("lock cleared at lock expiry instant", 0L, lockedUntil("alice"))
    }

    @Test
    fun `an expired lock then a wrong try gives count 1`() {
        register("alice", 1)
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count is 5", 5L, wrongCount("alice"))
        val clock = mutableClock(start.plusSeconds(16 * 60L))
        val store2 = AccountStore(temp.db, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        runBlocking { store2.attempts.recordWrong("alice") }
        assertEquals("count restarts at 1 after expired lock", 1L, wrongCount("alice"))
        assertEquals("lock cleared after expired lock", 0L, lockedUntil("alice"))
    }

    @Test
    fun `a partial count older than 15 minutes then a wrong try gives count 1`() {
        register("alice", 1)
        repeat(3) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count is 3", 3L, wrongCount("alice"))
        val clock = mutableClock(start.plusSeconds(16 * 60L))
        val store2 = AccountStore(temp.db, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        runBlocking { store2.attempts.recordWrong("alice") }
        assertEquals("stale partial count restarts at 1", 1L, wrongCount("alice"))
    }

    @Test
    fun `a partial count at exactly 15 minutes gives count 4`() {
        register("alice", 1)
        repeat(3) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count is 3", 3L, wrongCount("alice"))
        // Exactly at +15 minutes since last attempt: NOT more than 15 minutes, count continues.
        val clock = mutableClock(start.plusSeconds(15 * 60L))
        val store2 = AccountStore(temp.db, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        runBlocking { store2.attempts.recordWrong("alice") }
        assertEquals("count continues at exactly 15 minutes", 4L, wrongCount("alice"))
    }

    @Test
    fun `a partial count at 15 minutes plus 1 ms restarts at count 1`() {
        register("alice", 1)
        repeat(3) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count is 3", 3L, wrongCount("alice"))
        // More than 15 minutes since last attempt: count restarts at 1.
        val clock = mutableClock(start.plusSeconds(15 * 60L).plusMillis(1))
        val store2 = AccountStore(temp.db, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        runBlocking { store2.attempts.recordWrong("alice") }
        assertEquals("stale partial count restarts at 1 after 15 minutes + 1 ms", 1L, wrongCount("alice"))
    }

    @Test
    fun `clear deletes the row`() {
        register("alice", 1)
        runBlocking { store.attempts.recordWrong("alice") }
        assertEquals("alice row exists before clear", 1L, wrongCount("alice"))
        runBlocking { store.attempts.clear("alice") }
        assertNull("alice row deleted by clear", attemptOf("alice"))
    }

    @Test
    fun `two names get two rows`() {
        register("alice", 1)
        register("bob", 2)
        runBlocking { store.attempts.recordWrong("alice") }
        runBlocking { store.attempts.recordWrong("bob") }
        assertEquals("alice has a row", 1L, wrongCount("alice"))
        assertEquals("bob has a row", 1L, wrongCount("bob"))
    }

    @Test
    fun `the row cap evicts the only unlocked row and never a locked one`() {
        register("alice", 1)
        register("bob", 2)
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        runBlocking { store.attempts.recordWrong("bob") }
        val smallClock = mutableClock(start)
        val smallStore = SigninAttemptStore(temp.db, smallClock, maxRows = 2)
        runBlocking { smallStore.recordWrong("carol") }
        assertEquals("carol has a row after cap eviction", 1L, wrongCount("carol"))
        assertNull("bob (oldest unlocked) evicted by cap", attemptOf("bob"))
        assertTrue("alice (locked) not evicted by cap", attemptOf("alice") != null)
    }

    @Test
    fun `the row cap evicts the oldest unlocked row and keeps the newer ones`() {
        runBlocking { store.attempts.recordWrong("bob") }
        val second = SigninAttemptStore(temp.db, mutableClock(start.plusSeconds(1)), maxRows = 2)
        runBlocking { second.recordWrong("carol") }
        val third = SigninAttemptStore(temp.db, mutableClock(start.plusSeconds(2)), maxRows = 2)
        runBlocking { third.recordWrong("dave") }
        assertNull("bob (the oldest row) is evicted", attemptOf("bob"))
        assertEquals("carol (newer) is kept", 1L, wrongCount("carol"))
        assertEquals("dave (the new name) has a row", 1L, wrongCount("dave"))
    }

    @Test
    fun `the row cap never evicts a locked row even when it is the oldest`() {
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        val second = SigninAttemptStore(temp.db, mutableClock(start.plusSeconds(1)), maxRows = 2)
        runBlocking { second.recordWrong("bob") }
        val third = SigninAttemptStore(temp.db, mutableClock(start.plusSeconds(2)), maxRows = 2)
        runBlocking { third.recordWrong("carol") }
        assertEquals("alice (locked, oldest) is kept", 5L, wrongCount("alice"))
        assertNull("bob (the only unlocked row) is evicted", attemptOf("bob"))
        assertEquals("carol (the new name) has a row", 1L, wrongCount("carol"))
    }

    @Test
    fun `when every row is locked the new name is not inserted and nothing throws`() {
        val smallClock = mutableClock(start)
        val smallStore = SigninAttemptStore(temp.db, smallClock, maxRows = 1)
        repeat(5) { runBlocking { smallStore.recordWrong("alice") } }
        assertTrue("alice is locked", attemptOf("alice")!!.lockedUntilMs > start.toEpochMilli())
        runBlocking { smallStore.recordWrong("bob") }
        assertNull("bob not inserted when all rows locked", attemptOf("bob"))
    }

    @Test
    fun `verify counts an unknown name toward the limit`() {
        val before = wrongCount("nobody")
        assertEquals("nobody has no row before", -1L, before)
        runBlocking { store.verify("nobody", AccountFixtures.verifier(1)) }
        assertEquals("unknown name counted", 1L, wrongCount("nobody"))
    }

    @Test
    fun `verify counts a wrong verifier for an existing account`() {
        register("alice", 1)
        val flipped = AccountFixtures.flipBit(AccountFixtures.verifierBytes(1), 0)
        val bad = checkNotNull(AuthVerifier.fromBytes(flipped)) { "sync-api test helper: verifier bytes rejected" }
        runBlocking { store.verify("alice", bad) }
        assertEquals("wrong verifier counted", 1L, wrongCount("alice"))
    }

    @Test
    fun `five wrong tries lock and a right password afterwards still returns Verified and clears the row`() {
        register("alice", 1)
        repeat(5) {
            runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }
        }
        assertEquals("count reaches 5", 5L, wrongCount("alice"))
        assertTrue("alice is locked", lockedUntil("alice") > start.toEpochMilli())
        val result = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password inside lock returns Verified", result is VerifyResult.Verified)
        assertNull("row cleared after success", attemptOf("alice"))
    }

    @Test
    fun `the default row cap is 10000 and the wait is 15 minutes`() {
        assertEquals("default row cap", 10000, SigninAttemptStore.DEFAULT_MAX_ROWS)
        assertEquals("lock wait in milliseconds", 15L * 60 * 1000, SigninAttemptStore.LOCK_WAIT_MS)
    }

    @Test
    fun `a malformed username touches no row`() {
        runBlocking { store.verify("a b", AccountFixtures.verifier(1)) }
        assertNull("malformed username leaves no row", attemptOf("a b"))
        runBlocking { store.verify("", AccountFixtures.verifier(1)) }
        assertNull("empty username leaves no row", attemptOf(""))
    }

    private fun mutableClock(instant: Instant): Clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = instant
        }
}
