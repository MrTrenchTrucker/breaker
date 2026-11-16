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
    fun `five wrong tries reach count 5 and lock until now + 15 minutes`() {
        runBlocking { store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1)) }
        repeat(5) { runBlocking { store.attempts.recordWrong("alice") } }
        assertEquals("alice count after 5 wrong tries", 5L, attemptOf("alice")!!.wrongCount)
        val locked = attemptOf("alice")!!.lockedUntilMs
        assertEquals("lock is now + 15 minutes", start.toEpochMilli() + 15L * 60 * 1000, locked)
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
