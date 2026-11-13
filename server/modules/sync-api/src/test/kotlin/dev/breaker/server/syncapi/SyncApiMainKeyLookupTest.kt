package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The lookup main gives the internal listener, run here in the same shape against a real account store: the answer
// is the box PUBLIC key bytes of the account, and never the wrapped private key bytes that sit in the same stored
// keys. SyncApiMainWiringTest pins that main holds exactly this text.
internal class SyncApiMainKeyLookupTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        accountStore = AccountFixtures.newStore(temp)
    }

    // The same lambda text main passes to internalModule, with the store named as main names it.
    private fun lookup(): suspend (Long) -> ByteArray? = { accountStore.keysFor(it)?.boxPubkey }

    private fun register(name: String, seed: Int): Long =
        AccountFixtures.registered(
            runBlocking { accountStore.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed)) },
        ).accountId

    @Test
    fun `the lookup answers the box public key and not the wrapped private key`() {
        val accountId = register("alice", 1)
        val bundle = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val stored = runBlocking { accountStore.storeKeys(accountId, bundle) }
        assertTrue("sync-api: control: the keys must be stored first, got $stored", stored is StoreKeysResult.Stored)
        val answer = runBlocking { lookup()(accountId) }
        assertArrayEquals("sync-api: the lookup must answer the box public key", KeyPatterns.boxPubkey(), answer)
        assertFalse(
            "sync-api: control: the two key values must differ, or this test could not tell them apart",
            KeyPatterns.boxPubkey().contentEquals(KeyPatterns.wrappedBoxPrivkey()),
        )
    }

    @Test
    fun `the lookup answers null for an account with no keys and for an unknown account`() {
        val keyless = register("bob", 2)
        assertNull("sync-api: an account with no keys has no box public key", runBlocking { lookup()(keyless) })
        assertNull("sync-api: an unknown account has no box public key", runBlocking { lookup()(keyless + 1000L) })
    }
}
