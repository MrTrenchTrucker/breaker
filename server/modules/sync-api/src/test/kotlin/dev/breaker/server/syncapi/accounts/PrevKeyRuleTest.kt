package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.presetPrev
import dev.breaker.server.syncapi.auth.readKeyColumns
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.DeleteResult
import dev.breaker.server.syncapi.transcriptions.PushResult
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The previous key rule: prev_wrapped_dek goes as soon as no stored, not deleted transcription is below the key_version. */
internal class PrevKeyRuleTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore
    private lateinit var transcriptions: TranscriptionStore
    private var alice: Long = 0L

    @Before
    fun setup() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
        transcriptions = SyncRouteTestSupport.newStore(temp)
        alice = account("alice", 1, keyed = true)
    }

    private fun account(name: String, seed: Int, keyed: Boolean): Long {
        val registered = runBlocking {
            store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        }
        val id = AccountFixtures.registered(registered).accountId
        if (keyed) {
            val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
            val result = runBlocking { store.storeKeys(id, keys) }
            check(result is StoreKeysResult.Stored) { "sync-api test helper: the keys of $name were not stored" }
        }
        return id
    }

    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun push(accountId: Long, n: Int, keyVersion: Int): PushResult =
        runBlocking { transcriptions.push(accountId, clientId(n), keyVersion, SyncRouteTestSupport.ENCRYPTED) }

    private fun delete(accountId: Long, n: Int): DeleteResult = runBlocking { transcriptions.delete(accountId, clientId(n)) }

    private fun raiseKeyVersion(accountId: Long, keyVersion: Int) {
        runSql(temp, "UPDATE accounts SET key_version = $keyVersion WHERE id = $accountId")
    }

    private fun hold(accountId: Long) {
        presetPrev(temp, accountId, KeyPatterns.prevWrappedDek())
    }

    private fun applyRule(accountId: Long) {
        runBlocking { temp.db.transaction { connection -> PrevKeyRule.clearIfNoOldRows(connection, accountId) } }
    }

    private fun assertHeld(accountId: Long, label: String) {
        val prev = readKeyColumns(temp, accountId).prevWrappedDek
        assertArrayEquals("sync-api: prev_wrapped_dek must be kept $label", KeyPatterns.prevWrappedDek(), prev)
    }

    private fun assertCleared(accountId: Long, label: String) {
        assertNull("sync-api: prev_wrapped_dek must be cleared $label", readKeyColumns(temp, accountId).prevWrappedDek)
    }

    private fun assertPushed(expected: PushResult, actual: PushResult) {
        assertEquals("sync-api: push result", expected, actual)
    }

    @Test
    fun `prev is cleared when the account has no transcriptions`() {
        raiseKeyVersion(alice, 2)
        hold(alice)
        assertHeld(alice, "before the rule runs")

        applyRule(alice)

        assertCleared(alice, "with no rows")
    }

    @Test
    fun `prev is kept while a not deleted row below the key_version exists`() {
        assertPushed(PushResult.Stored, push(alice, 0, 1))
        raiseKeyVersion(alice, 2)
        hold(alice)

        applyRule(alice)

        assertHeld(alice, "with an old row")
    }

    @Test
    fun `a push that replaces the last old row clears prev`() {
        assertPushed(PushResult.Stored, push(alice, 0, 1))
        raiseKeyVersion(alice, 2)
        hold(alice)
        assertHeld(alice, "before the push")

        assertPushed(PushResult.Replaced, push(alice, 0, 2))

        assertCleared(alice, "after the last old row was replaced")
    }

    @Test
    fun `a push that replaces one of two old rows keeps prev and the second push clears it`() {
        assertPushed(PushResult.Stored, push(alice, 0, 1))
        assertPushed(PushResult.Stored, push(alice, 1, 1))
        raiseKeyVersion(alice, 2)
        hold(alice)

        assertPushed(PushResult.Replaced, push(alice, 0, 2))
        assertHeld(alice, "while the second old row remains")

        assertPushed(PushResult.Replaced, push(alice, 1, 2))
        assertCleared(alice, "after the second old row was replaced")
    }

    @Test
    fun `a delete of the last old row clears prev`() {
        assertPushed(PushResult.Stored, push(alice, 0, 1))
        raiseKeyVersion(alice, 2)
        hold(alice)
        assertHeld(alice, "before the delete")

        assertEquals("sync-api: delete result", DeleteResult.Deleted, delete(alice, 0))

        assertCleared(alice, "after the last old row was deleted")
    }

    @Test
    fun `a delete of a current row keeps prev while an old row remains and the delete of the old row clears it`() {
        assertPushed(PushResult.Stored, push(alice, 0, 1))
        raiseKeyVersion(alice, 2)
        assertPushed(PushResult.Stored, push(alice, 1, 2))
        hold(alice)

        assertEquals("sync-api: delete of the current row", DeleteResult.Deleted, delete(alice, 1))
        assertHeld(alice, "while the old row remains")

        assertEquals("sync-api: delete of the old row", DeleteResult.Deleted, delete(alice, 0))
        assertCleared(alice, "after the old row was deleted")
    }

    @Test
    fun `a deleted old row does not hold prev`() {
        assertPushed(PushResult.Stored, push(alice, 0, 1))
        assertEquals("sync-api: setup delete", DeleteResult.Deleted, delete(alice, 0))
        raiseKeyVersion(alice, 2)
        hold(alice)

        applyRule(alice)

        assertCleared(alice, "with only a deleted old row")
    }

    @Test
    fun `a keyless account with no prev is left as it was`() {
        val carol = account("carol", 3, keyed = false)

        applyRule(carol)

        val columns = readKeyColumns(temp, carol)
        assertNull("sync-api: prev_wrapped_dek", columns.prevWrappedDek)
        assertNull("sync-api: wrapped_dek", columns.wrappedDek)
        assertNull("sync-api: rekeyed_at", columns.rekeyedAt)
        assertEquals("sync-api: key_version", 0, columns.keyVersion)
    }

    @Test
    fun `an old row of a second account does not hold prev of this account`() {
        val bob = account("bob", 2, keyed = true)
        assertPushed(PushResult.Stored, push(bob, 0, 1))
        raiseKeyVersion(bob, 2)
        raiseKeyVersion(alice, 2)
        hold(alice)

        applyRule(alice)

        assertCleared(alice, "when only the second account has an old row")
    }

    @Test
    fun `a run for one account does not clear prev of a second account`() {
        val bob = account("bob", 2, keyed = true)
        raiseKeyVersion(bob, 2)
        hold(bob)
        raiseKeyVersion(alice, 2)
        hold(alice)

        applyRule(alice)

        assertCleared(alice, "for the account the rule ran for")
        assertHeld(bob, "for the second account")
    }
}
