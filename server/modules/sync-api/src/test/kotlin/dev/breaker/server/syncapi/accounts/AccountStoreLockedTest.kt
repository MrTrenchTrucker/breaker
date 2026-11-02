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

internal class AccountStoreLockedTest : TempDatabaseTest() {

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

    private fun attemptOf(foldedName: String) =
        runBlocking { store.attempts.attemptOf(foldedName) }

    @Test
    fun `fifth wrong try is plain Rejected and sets the lock`() {
        register("alice", 1)
        repeat(4) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        val fifth = runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }
        assertEquals("fifth wrong is Rejected", VerifyResult.Rejected, fifth)
        assertEquals("count is 5", 5L, attemptOf("alice")!!.wrongCount)
        assertTrue("alice is locked", attemptOf("alice")!!.lockedUntilMs > start.toEpochMilli())
    }

    @Test
    fun `sixth wrong try inside the wait returns Locked and touches nothing`() {
        register("alice", 1)
        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        val lockedBefore = attemptOf("alice")!!.lockedUntilMs
        val countBefore = attemptOf("alice")!!.wrongCount
        val sixth = runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }
        assertTrue("sixth wrong inside wait is Locked", sixth is VerifyResult.Locked)
        val locked = (sixth as VerifyResult.Locked).secondsRemaining
        assertTrue("secondsRemaining is positive", locked > 0L)
        assertEquals("count untouched inside wait", countBefore, attemptOf("alice")!!.wrongCount)
        assertEquals("locked_until untouched inside wait", lockedBefore, attemptOf("alice")!!.lockedUntilMs)
    }

    @Test
    fun `right password inside the wait returns Locked and touches nothing`() {
        register("alice", 1)
        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        val lockedBefore = attemptOf("alice")!!.lockedUntilMs
        val result = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password inside wait is Locked", result is VerifyResult.Locked)
        assertEquals("count untouched after right password inside wait", 5L, attemptOf("alice")!!.wrongCount)
        assertEquals("locked_until untouched after right password inside wait", lockedBefore, attemptOf("alice")!!.lockedUntilMs)
    }

    @Test
    fun `right password after the lock expires returns Verified and clears the row`() {
        register("alice", 1)
        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        assertTrue("alice is locked", attemptOf("alice")!!.lockedUntilMs > start.toEpochMilli())
        val clock = mutableClock(start.plusSeconds(16 * 60L))
        val store2 = AccountFixtures.newStore(temp, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        val result = runBlocking { store2.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password after expiry returns Verified", result is VerifyResult.Verified)
        assertNull("row cleared after success", attemptOf("alice"))
    }

    @Test
    fun `verify fail-open on DROP TABLE wrong verifier returns Rejected nothing throws`() {
        register("alice", 1)
        runBlocking {
            temp.db.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE signin_attempts")
                }
            }
        }
        val wrong = runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }
        assertEquals("wrong verifier after DROP TABLE is Rejected", VerifyResult.Rejected, wrong)
        val right = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right verifier after DROP TABLE is Verified", right is VerifyResult.Verified)
        val malformed = runBlocking { store.verify("a b", AccountFixtures.verifier(1)) }
        assertEquals("malformed after DROP TABLE is Rejected", VerifyResult.Rejected, malformed)
        val unknown = runBlocking { store.verify("nobody", AccountFixtures.verifier(1)) }
        assertEquals("an unknown name after DROP TABLE is Rejected", VerifyResult.Rejected, unknown)
    }

    @Test
    fun `an unknown name locked by five wrong tries returns Locked with 900 seconds`() {
        runBlocking { store.verify("nobody", AccountFixtures.verifier(1)) }
        repeat(3) { runBlocking { store.verify("nobody", AccountFixtures.verifier(2)) } }
        val fifth = runBlocking { store.verify("nobody", AccountFixtures.verifier(2)) }
        assertEquals("fifth wrong for unknown name is Rejected", VerifyResult.Rejected, fifth)
        val sixth = runBlocking { store.verify("nobody", AccountFixtures.verifier(1)) }
        assertTrue("sixth wrong for unknown name inside wait is Locked", sixth is VerifyResult.Locked)
        val seconds = (sixth as VerifyResult.Locked).secondsRemaining
        assertEquals("unknown name lock gives 900 seconds", 900L, seconds)
    }

    private fun mutableClock(instant: Instant): Clock =
        object : Clock() {
            override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): java.time.Instant = instant
        }
}
