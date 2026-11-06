package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A pending name takes the same path as an active name with a wrong verifier: one full hash, a
 * counted wrong try, and a lock after five. Recording a wrong try never reads account state, on
 * purpose: a no-op for pending accounts would tell them apart from active and unknown names again.
 */
internal class AccountStorePendingCountsTest : TempDatabaseTest() {

    /** A [VerifierHasher] that counts every [matches] call, then delegates to the real derivation. */
    private class PendingCountingHasher(iterations: Int, random: SecureRandom) :
        VerifierHasher(iterations, random) {
        var matchesCalls: Int = 0
        override fun matches(verifier: AuthVerifier, stored: StoredVerifier): Boolean {
            matchesCalls += 1
            return super.matches(verifier, stored)
        }
    }

    private lateinit var temp: TempDatabase
    private lateinit var hasher: PendingCountingHasher
    private lateinit var store: AccountStore

    // Each test registers its own names. Every name has its own seed, so ids and values never coincide.
    @Before
    fun openWorld() {
        temp = openTemp()
        hasher = PendingCountingHasher(AccountFixtures.FAST_ITERATIONS, SecureRandom())
        store = AccountFixtures.newStore(temp, hasher)
    }

    private fun register(name: String, seed: Int): Long =
        AccountFixtures.registered(
            runBlocking {
                store.register(
                    name,
                    AccountFixtures.salt(seed),
                    AccountFixtures.validKdf,
                    1,
                    AccountFixtures.verifier(seed),
                )
            },
        ).accountId

    private fun markPending(id: Long, value: Int = 1) {
        runSql(temp, "UPDATE accounts SET reset_pending = $value WHERE id = $id")
    }

    private fun verify(name: String, seed: Int): VerifyResult =
        runBlocking { store.verify(name, AccountFixtures.verifier(seed)) }

    private fun attemptOf(folded: String) = runBlocking { store.attempts.attemptOf(folded) }

    @Test
    fun `a pending name with a wrong verifier is Rejected with one hash and one counted try`() {
        val alice = register("alice", 1)
        markPending(alice)
        hasher.matchesCalls = 0
        assertEquals("sync-api: wrong verifier on a pending name", VerifyResult.Rejected, verify("alice", 5))
        assertEquals("sync-api: hash calls", 1, hasher.matchesCalls)
        assertEquals("sync-api: the wrong try row", 1L, checkNotNull(attemptOf("alice")).wrongCount)
    }

    @Test
    fun `a pending name with the old right verifier is Rejected with one hash and one counted try`() {
        val alice = register("alice", 1)
        markPending(alice)
        hasher.matchesCalls = 0
        assertEquals(
            "sync-api: old right verifier on a pending name",
            VerifyResult.Rejected,
            verify("alice", 1),
        )
        assertEquals("sync-api: hash calls", 1, hasher.matchesCalls)
        assertEquals("sync-api: the wrong try row", 1L, checkNotNull(attemptOf("alice")).wrongCount)
    }

    @Test
    fun `five wrong tries on a pending name lock it so the sixth answers Locked for 900 seconds`() {
        val alice = register("alice", 1)
        markPending(alice)
        repeat(5) { assertEquals("sync-api: try before the lock", VerifyResult.Rejected, verify("alice", 5)) }
        assertEquals("sync-api: the sixth try", VerifyResult.Locked(900L), verify("alice", 5))
    }

    @Test
    fun `pending active and unknown names give the same six answers over six wrong tries`() {
        val alice = register("alice", 1)
        register("bob", 2)
        val carol = register("carol", 3)
        register("brenda", 4)
        markPending(alice)
        markPending(carol)
        val names = listOf("alice", "bob", "carol", "brenda", "ghost", "nobody")
        val rejectedFive = List<VerifyResult>(5) { VerifyResult.Rejected }
        val expected: List<VerifyResult> = rejectedFive + VerifyResult.Locked(900L)
        for ((index, name) in names.withIndex()) {
            val answers = (1..6).map { verify(name, 5) }
            assertEquals("sync-api: six answers for name $index", expected, answers)
        }
    }

    @Test
    fun `a pending name an active wrong verifier and an unknown name each hash exactly once`() {
        val alice = register("alice", 1)
        markPending(alice)
        register("bob", 2)
        hasher.matchesCalls = 0
        verify("alice", 5)
        assertEquals("sync-api: hash calls, pending name", 1, hasher.matchesCalls)
        hasher.matchesCalls = 0
        verify("bob", 5)
        assertEquals("sync-api: hash calls, active name", 1, hasher.matchesCalls)
        hasher.matchesCalls = 0
        verify("ghost", 5)
        assertEquals("sync-api: hash calls, unknown name", 1, hasher.matchesCalls)
    }

    @Test
    fun `a locked pending name answers Locked with no hash at all`() {
        val alice = register("alice", 1)
        repeat(5) { verify("alice", 5) }
        markPending(alice)
        hasher.matchesCalls = 0
        assertEquals("sync-api: locked pending name", VerifyResult.Locked(900L), verify("alice", 5))
        assertEquals("sync-api: hash calls", 0, hasher.matchesCalls)
    }

    @Test
    fun `with the limit off a pending name is Rejected with one hash each and keeps no row`() {
        runBlocking { store.settings.setWrongPwLimitEnabled(false) }
        val alice = register("alice", 1)
        markPending(alice)
        hasher.matchesCalls = 0
        assertEquals("sync-api: wrong verifier, limit off", VerifyResult.Rejected, verify("alice", 5))
        assertEquals("sync-api: hash calls, wrong verifier", 1, hasher.matchesCalls)
        hasher.matchesCalls = 0
        assertEquals("sync-api: old right verifier, limit off", VerifyResult.Rejected, verify("alice", 1))
        assertEquals("sync-api: hash calls, old right verifier", 1, hasher.matchesCalls)
        val tail = (1..4).map { verify("alice", 5) }
        assertTrue(
            "sync-api: no lock with the limit off",
            tail.all { answer -> answer == VerifyResult.Rejected },
        )
        assertNull("sync-api: a row was kept with the limit off", attemptOf("alice"))
    }

    @Test
    fun `with the limit off an active wrong verifier hashes once and keeps no row`() {
        runBlocking { store.settings.setWrongPwLimitEnabled(false) }
        register("bob", 2)
        hasher.matchesCalls = 0
        assertEquals("sync-api: active wrong verifier, limit off", VerifyResult.Rejected, verify("bob", 5))
        assertEquals("sync-api: hash calls, active name", 1, hasher.matchesCalls)
        assertNull("sync-api: a row was kept for an active name", attemptOf("bob"))
    }

    @Test
    fun `the same name with the pending mark cleared and the right verifier is Verified`() {
        val alice = register("alice", 1)
        markPending(alice)
        markPending(alice, 0)
        val result = verify("alice", 1)
        assertTrue(
            "sync-api: control, the right verifier after the mark is cleared: $result",
            result is VerifyResult.Verified,
        )
    }
}
