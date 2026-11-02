package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class AccountStoreCountingTest : TempDatabaseTest() {

    private val start = Instant.ofEpochMilli(AccountFixtures.FIXED_MILLIS)

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun openStore() {
        temp = openTemp()
        store = AccountFixtures.newStore(
            temp,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            AccountFixtures.fixedClock,
        )
    }

    private fun register(name: String, seed: Int) =
        AccountFixtures.registered(runBlocking { store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed)) })

    private fun attemptOf(foldedName: String) =
        runBlocking { store.attempts.attemptOf(foldedName) }

    @Test
    fun `unknown name counts through verify`() {
        runBlocking { store.verify("nobody", AccountFixtures.verifier(1)) }
        assertEquals("unknown name count is 1", 1L, attemptOf("nobody")!!.wrongCount)
    }

    @Test
    fun `wrong verifier counts through verify`() {
        register("alice", 1)
        val flipped = AccountFixtures.flipBit(AccountFixtures.verifierBytes(1), 0)
        val bad = checkNotNull(AuthVerifier.fromBytes(flipped)) { "sync-api test helper: verifier bytes rejected" }
        runBlocking { store.verify("alice", bad) }
        assertEquals("wrong verifier count is 1", 1L, attemptOf("alice")!!.wrongCount)
    }

    @Test
    fun `five wrong tries lock and right password returns Verified and clears`() {
        register("alice", 1)
        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        assertEquals("count reaches 5", 5L, attemptOf("alice")!!.wrongCount)
        val locked = attemptOf("alice")!!.lockedUntilMs
        assertTrue("alice is locked", locked > AccountFixtures.FIXED_MILLIS)
        // Right password inside the wait returns Locked and touches nothing.
        val inside = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password inside wait is Locked", inside is VerifyResult.Locked)
        assertNotNull("row still exists inside wait", attemptOf("alice"))
        assertEquals("count untouched inside wait", 5L, attemptOf("alice")!!.wrongCount)
        assertEquals("locked_until untouched inside wait", locked, attemptOf("alice")!!.lockedUntilMs)
        // Move the clock past the wait: right password returns Verified and clears the row.
        val clock = mutableClock(start.plusSeconds(16 * 60L))
        val store2 = AccountFixtures.newStore(temp, VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS), clock)
        val result = runBlocking { store2.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password after wait returns Verified", result is VerifyResult.Verified)
        assertNull("row cleared after success", attemptOf("alice"))
    }

    @Test
    fun `malformed username touches no row`() {
        runBlocking { store.verify("a b", AccountFixtures.verifier(1)) }
        assertNull("malformed username leaves no row", attemptOf("a b"))
    }

    private fun mutableClock(instant: Instant): Clock =
        object : Clock() {
            override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): java.time.Instant = instant
        }
}
