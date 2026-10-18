package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.accounts.AccountFixtures.accountCount
import dev.breaker.server.syncapi.accounts.AccountFixtures.found
import dev.breaker.server.syncapi.accounts.AccountFixtures.flipBit
import dev.breaker.server.syncapi.accounts.AccountFixtures.registered
import dev.breaker.server.syncapi.accounts.AccountFixtures.salt
import dev.breaker.server.syncapi.accounts.AccountFixtures.validKdf
import dev.breaker.server.syncapi.accounts.AccountFixtures.verifier
import dev.breaker.server.syncapi.accounts.AccountFixtures.verifierBytes
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class AccountStoreLookupTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun openStore() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun register(name: String, seed: Int, kdf: KdfParams = validKdf, kdfVersion: Int = 1): RegisterResult.Registered =
        registered(runBlocking { store.register(name, salt(seed), kdf, kdfVersion, verifier(seed)) })

    private fun saltFor(name: String): SaltLookup = runBlocking { store.saltFor(name) }

    private fun verify(name: String, subject: AuthVerifier): VerifyResult = runBlocking { store.verify(name, subject) }

    @Test
    fun `saltFor returns the salt, params and version each account was registered with`() {
        val firstKdf = KdfParams(100000, 4, 2)
        val secondKdf = KdfParams(200000, 7, 3)
        register("alice", 1, firstKdf, 1)
        register("bob", 2, secondKdf, 1)
        val alice = found(saltFor("alice"))
        val bob = found(saltFor("bob"))
        assertArrayEquals("alice salt", salt(1), alice.salt)
        assertEquals("alice kdf", firstKdf, alice.kdf)
        assertEquals("alice kdf version", 1, alice.kdfVersion)
        assertArrayEquals("bob salt", salt(2), bob.salt)
        assertEquals("bob kdf", secondKdf, bob.kdf)
        assertEquals("bob kdf version", 1, bob.kdfVersion)
    }

    @Test
    fun `saltFor finds an account under another letter case`() {
        register("bob", 1)
        assertArrayEquals("BOB must find bob", salt(1), found(saltFor("BOB")).salt)
    }

    @Test
    fun `saltFor answers NoSuchAccount for unknown and invalid names and leaves the table alone`() {
        register("alice", 1)
        val names = listOf("nobody", "", "a".repeat(65), "a b", "a'; DROP TABLE accounts;--")
        for (name in names) {
            assertEquals("'$name'", SaltLookup.NoSuchAccount, saltFor(name))
        }
        assertEquals("the accounts table must still hold its row", 1L, accountCount(temp))
        assertArrayEquals("alice must be unchanged", salt(1), found(saltFor("alice")).salt)
    }

    @Test
    fun `an invalid name is answered without touching the database`() {
        register("alice", 1)
        temp.closeKeepingFiles()
        assertEquals("saltFor on a closed database", SaltLookup.NoSuchAccount, saltFor("a b"))
        assertEquals("verify on a closed database", VerifyResult.Rejected, verify("", verifier(1)))
        // Control: a valid name does reach the database, which is closed, so it must fail.
        TestDatabases.expectFailure<IllegalStateException>("saltFor with a valid name on a closed database") { saltFor("alice") }
    }

    @Test
    fun `verify accepts the right verifier and reports the id and role from registration`() {
        val admin = register("alice", 1)
        val user = register("bob", 2)
        assertEquals("admin", VerifyResult.Verified(admin.accountId, Role.ADMIN), verify("alice", verifier(1)))
        assertEquals("user", VerifyResult.Verified(user.accountId, Role.USER), verify("bob", verifier(2)))
        assertTrue("the two accounts must have different ids", admin.accountId != user.accountId)
    }

    @Test
    fun `verify finds the account under another letter case`() {
        val bob = register("bob", 1)
        assertEquals("BOB", VerifyResult.Verified(bob.accountId, Role.ADMIN), verify("BOB", verifier(1)))
    }

    @Test
    fun `verify rejects a verifier with one bit flipped`() {
        register("alice", 1)
        val flipped = checkNotNull(AuthVerifier.fromBytes(flipBit(verifierBytes(1), 0)))
        assertEquals("flipped bit", VerifyResult.Rejected, verify("alice", flipped))
    }

    @Test
    fun `verify rejects another account's verifier`() {
        register("alice", 1)
        register("bob", 2)
        assertEquals("bob's verifier on alice", VerifyResult.Rejected, verify("alice", verifier(2)))
    }

    @Test
    fun `verify rejects unknown and invalid usernames`() {
        register("alice", 1)
        assertEquals("unknown name", VerifyResult.Rejected, verify("nobody", verifier(1)))
        assertEquals("invalid name", VerifyResult.Rejected, verify("a b", verifier(1)))
    }

    @Test
    fun `verify uses the cost stored with the account, not the cost of the store's hasher`() {
        val admin = register("alice", 1)
        val slowerStore = AccountFixtures.newStore(temp, VerifierHasher(iterations = 1))
        val result = runBlocking { slowerStore.verify("alice", verifier(1)) }
        assertEquals("a store hashing at cost 1 must still verify a cost 1000 account", VerifyResult.Verified(admin.accountId, Role.ADMIN), result)
    }
}
