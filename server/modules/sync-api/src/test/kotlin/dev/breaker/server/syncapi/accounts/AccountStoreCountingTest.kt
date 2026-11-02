package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class AccountStoreCountingTest : TempDatabaseTest() {

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
        val result = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password returns Verified", result is VerifyResult.Verified)
        assertNull("row cleared after success", attemptOf("alice"))
    }

    @Test
    fun `malformed username touches no row`() {
        runBlocking { store.verify("a b", AccountFixtures.verifier(1)) }
        assertNull("malformed username leaves no row", attemptOf("a b"))
    }
}
