package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.readKeyColumns
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An account waiting for a reset: verify refuses it without a count, and no key can be stored. */
internal class AccountStorePendingTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    // alice (id 1, typed with a capital) is registered with verifier seed 1 and bob (id 2) with seed 2.
    @Before
    fun openWorld() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
        alice = register("Alice", 1)
        bob = register("bob", 2)
    }

    private fun register(name: String, seed: Int): Long =
        AccountFixtures.registered(
            runBlocking {
                store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
            },
        ).accountId

    private fun markPending(id: Long, value: Int = 1) {
        runSql(temp, "UPDATE accounts SET reset_pending = $value WHERE id = $id")
    }

    private fun verify(name: String, seed: Int): VerifyResult = runBlocking { store.verify(name, AccountFixtures.verifier(seed)) }

    private fun attemptOf(name: String) = runBlocking { store.attempts.attemptOf(name) }

    private fun bundle(): KeyBundle = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())

    @Test
    fun `verify with the right verifier on a pending account is Rejected and leaves the wrong try row`() {
        repeat(2) { runBlocking { store.attempts.recordWrong("alice") } }
        markPending(alice)
        assertEquals("sync-api: right verifier on a pending account", VerifyResult.Rejected, verify("Alice", 1))
        assertEquals("sync-api: the wrong try row", 2L, checkNotNull(attemptOf("alice")).wrongCount)
    }

    @Test
    fun `verify with a wrong verifier on a pending account is Rejected and counts nothing`() {
        markPending(alice)
        assertEquals("sync-api: wrong verifier", VerifyResult.Rejected, verify("Alice", 5))
        assertNull("sync-api: a wrong try was counted", attemptOf("alice"))
    }

    @Test
    fun `with the limit off a pending account is Rejected for the right and the wrong verifier and counts nothing`() {
        runBlocking { store.settings.setWrongPwLimitEnabled(false) }
        markPending(alice)
        assertEquals("sync-api: right verifier, limit off", VerifyResult.Rejected, verify("Alice", 1))
        assertEquals("sync-api: wrong verifier, limit off", VerifyResult.Rejected, verify("Alice", 5))
        assertNull("sync-api: a wrong try was counted", attemptOf("alice"))
    }

    @Test
    fun `the same right verifier verifies once the pending mark is 0`() {
        markPending(alice)
        markPending(alice, 0)
        assertTrue("sync-api: control", verify("Alice", 1) is VerifyResult.Verified)
    }

    @Test
    fun `a pending account does not stop another account from verifying`() {
        markPending(alice)
        val result = verify("bob", 2)
        assertTrue("sync-api: bob must verify: $result", result is VerifyResult.Verified)
        assertEquals("sync-api: the account of the result", bob, (result as VerifyResult.Verified).accountId)
    }

    @Test
    fun `resetPendingOf answers true for a pending account false for another and null for no account`() {
        markPending(alice)
        assertEquals("sync-api: pending", true, runBlocking { store.resetPendingOf(alice) })
        assertEquals("sync-api: not pending", false, runBlocking { store.resetPendingOf(bob) })
        assertNull("sync-api: no account", runBlocking { store.resetPendingOf(bob + 1000L) })
    }

    @Test
    fun `storeKeys on a pending keyless account answers ResetPending and stores nothing`() {
        markPending(alice)
        assertEquals("sync-api: result", StoreKeysResult.ResetPending, runBlocking { store.storeKeys(alice, bundle()) })
        val columns = readKeyColumns(temp, alice)
        assertNull("sync-api: wrapped_dek", columns.wrappedDek)
        assertNull("sync-api: wrapped_box_privkey", columns.wrappedBoxPrivkey)
        assertNull("sync-api: box_pubkey", columns.boxPubkey)
        assertEquals("sync-api: key_version", 0, columns.keyVersion)
    }

    @Test
    fun `storeKeys on a pending keyed account answers ResetPending and not AlreadyKeyed`() {
        runBlocking { store.storeKeys(alice, bundle()) }
        markPending(alice)
        assertEquals("sync-api: result", StoreKeysResult.ResetPending, runBlocking { store.storeKeys(alice, bundle()) })
        val columns = readKeyColumns(temp, alice)
        assertArrayEquals("sync-api: wrapped_dek", KeyPatterns.wrappedDek(), columns.wrappedDek)
        assertEquals("sync-api: key_version", 1, columns.keyVersion)
    }

    @Test
    fun `storeKeys on a keyless account that is not pending still stores and another pending account does not matter`() {
        markPending(bob)
        val result = runBlocking { store.storeKeys(alice, bundle()) }
        assertTrue("sync-api: result: $result", result is StoreKeysResult.Stored)
        assertEquals("sync-api: key_version", 1, readKeyColumns(temp, alice).keyVersion)
        assertNull("sync-api: bob was keyed", readKeyColumns(temp, bob).wrappedDek)
    }
}
