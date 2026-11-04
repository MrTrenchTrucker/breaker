package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.security.SecureRandom
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a login for a name that has no account must cost the same as a login for a name that
 * does. The store runs one [VerifierHasher.matches] against a fixed dummy verifier on the
 * not-found path, before it answers Rejected, so the two paths take the same time. A counting
 * hasher proves the not-found path really runs the hash (one call) and that the found path
 * still runs exactly one (the real one).
 */
internal class AccountStoreTimingDecoyTest : TempDatabaseTest() {

    /** A [VerifierHasher] that counts every [matches] call, then delegates to the real PBKDF2. */
    private class CountingHasher(iterations: Int, random: SecureRandom) : VerifierHasher(iterations, random) {
        var matchesCalls: Int = 0
        override fun matches(verifier: AuthVerifier, stored: StoredVerifier): Boolean {
            matchesCalls += 1
            return super.matches(verifier, stored)
        }
    }

    private fun store(temp: TempDatabase, hasher: VerifierHasher): AccountStore =
        AccountStore(temp.db, hasher, Clock.systemUTC())

    @Test
    fun `a not found login runs the verifier hash once against the dummy`() {
        val temp = openTemp()
        val hasher = CountingHasher(AccountFixtures.FAST_ITERATIONS, SecureRandom())
        val store = store(temp, hasher)
        // Register one account so the store is not empty; the login below targets a different name.
        runBlocking {
            AccountFixtures.registered(
                store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1)),
            )
        }

        hasher.matchesCalls = 0
        val result = runBlocking { store.verify("ghost", AccountFixtures.verifier(9)) }

        assertEquals(
            "the not-found path must run the verifier hash exactly once (the dummy)",
            1,
            hasher.matchesCalls,
        )
        assertTrue("the not-found login is Rejected", result == VerifyResult.Rejected)
    }

    @Test
    fun `a found wrong verifier login runs the verifier hash exactly once (the real one)`() {
        val temp = openTemp()
        val hasher = CountingHasher(AccountFixtures.FAST_ITERATIONS, SecureRandom())
        val store = store(temp, hasher)
        runBlocking {
            AccountFixtures.registered(
                store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1)),
            )
        }

        hasher.matchesCalls = 0
        val result = runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }

        assertEquals(
            "the found wrong-verifier path runs the real hash exactly once (no dummy)",
            1,
            hasher.matchesCalls,
        )
        assertTrue("a wrong verifier is Rejected", result == VerifyResult.Rejected)
    }
}
