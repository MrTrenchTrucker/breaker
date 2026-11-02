package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class SigninAttemptStoreLockTest : TempDatabaseTest() {

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

    @Test
    fun `retryAfterSeconds is never less than 1`() {
        val result = SigninAttemptStore.retryAfterSeconds(0L, start.toEpochMilli())
        assertEquals("not locked means 1", 1L, result)
    }

    @Test
    fun `retryAfterSeconds is 1 for any positive sub-second remainder`() {
        val now = start.toEpochMilli()
        val future = now + 1
        assertEquals("1 ms left rounds up to 1 second", 1L, SigninAttemptStore.retryAfterSeconds(future, now))
        assertEquals("500 ms left rounds up to 1 second", 1L, SigninAttemptStore.retryAfterSeconds(now + 500, now))
    }

    @Test
    fun `retryAfterSeconds rounds up whole seconds`() {
        val now = start.toEpochMilli()
        assertEquals("1001 ms -> 2 seconds", 2L, SigninAttemptStore.retryAfterSeconds(now + 1001, now))
        assertEquals("900000 ms -> 900 seconds", 900L, SigninAttemptStore.retryAfterSeconds(now + 900000, now))
        assertEquals("450000 ms -> 450 seconds", 450L, SigninAttemptStore.retryAfterSeconds(now + 450000, now))
    }

    @Test
    fun `retryAfterSeconds minimum is 1 even for the tiniest remainder`() {
        val now = start.toEpochMilli()
        assertEquals("tiny positive remainder is 1", 1L, SigninAttemptStore.retryAfterSeconds(now + 1, now))
    }

    @Test
    fun `five wrong tries reach count 5 and lock until now + 15 minutes`() {
        runBlocking { store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1)) }
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count after 5 wrong tries", 5L, attemptOf("alice")!!.wrongCount)
        val locked = attemptOf("alice")!!.lockedUntilMs
        assertEquals("lock is now + 15 minutes", start.toEpochMilli() + 15L * 60 * 1000, locked)
        val seconds = SigninAttemptStore.retryAfterSeconds(locked, start.toEpochMilli())
        assertEquals("retry after is 900 seconds", 900L, seconds)
    }

    @Test
    fun `a try inside the lock changes nothing`() {
        runBlocking { store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1)) }
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        val lockedBefore = attemptOf("alice")!!.lockedUntilMs
        val countBefore = attemptOf("alice")!!.wrongCount
        val clock = mutableClock(start.plusSeconds(1 * 60L))
        val store2 = AccountStore(temp.db, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        runBlocking { store2.attempts.recordWrong("alice") }
        assertEquals("count unchanged inside the lock", countBefore, attemptOf("alice")!!.wrongCount)
        assertEquals("locked_until unchanged inside the lock", lockedBefore, attemptOf("alice")!!.lockedUntilMs)
    }

    @Test
    fun `attemptOf returns null when there is no row`() {
        assertNull("unknown name has no attempt row", runBlocking { store.attempts.attemptOf("nobody") })
    }

    private fun attemptOf(foldedName: String) =
        runBlocking { store.attempts.attemptOf(foldedName) }

    private fun mutableClock(instant: Instant): Clock =
        object : Clock() {
            override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): java.time.Instant = instant
        }
}
