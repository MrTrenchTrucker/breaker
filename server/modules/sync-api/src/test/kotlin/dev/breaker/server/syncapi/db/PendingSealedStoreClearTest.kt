package dev.breaker.server.syncapi.db

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.reset.ResetAccountResult
import dev.breaker.server.syncapi.reset.ResetStore
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.SQLException

/**
 * Clearing the pending_sealed rows happens where box_pubkey is written: the reset (it sets box_pubkey to
 * NULL) and the first key store after it. The password change is in PasswordChangeStorePendingSealedTest.
 * The clear runs on the caller's connection with no transaction of its own.
 */
internal class PendingSealedStoreClearTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accounts: AccountStore
    private lateinit var tokens: TokenStore
    private lateinit var store: ResetStore

    private val forcedClearTrigger =
        "CREATE TRIGGER forced_clear_failure AFTER DELETE ON pending_sealed " +
            "WHEN OLD.account_id IS NOT NULL BEGIN SELECT RAISE(ABORT, 'forced_clear'); END"

    @Before
    fun setup() {
        temp = openTemp()
        accounts = AccountFixtures.newStore(temp)
        tokens = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        store = ResetStore(accounts, tokens)
    }

    private fun register(name: String, seed: Int): Long =
        TokenFixtures.register(temp, name, seed).accountId

    private fun keyUp(id: Long) {
        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val result = runBlocking { accounts.storeKeys(id, keys) }
        if (result !is StoreKeysResult.Stored) throw AssertionError("sync-api: keys not stored, got $result")
    }

    private fun pendingRow(accountId: Long, id: String) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES (?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setLong(2, accountId)
                    statement.setString(3, "sealed-$id")
                    statement.setString(4, "2026-11-10T00:00:00.000Z")
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun countPending(accountId: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT count(*) FROM pending_sealed WHERE account_id = ?").use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "sync-api: no count row" }
                        rows.getLong(1)
                    }
                }
            }
        }

    private fun reset(name: String): String {
        val result = runBlocking { store.resetAccount(name) }
        if (result !is ResetAccountResult.Reset) throw AssertionError("sync-api: reset of $name gave $result")
        return result.code
    }

    private fun expectForcedFailure(block: () -> Unit): SQLException {
        return try {
            block()
            throw AssertionError("sync-api: expected forced failure but nothing was thrown")
        } catch (e: SQLException) {
            e
        }
    }

    @Test
    fun `reset clears the account's own pending_sealed rows`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        keyUp(alice)
        keyUp(bob)
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        pendingRow(alice, "11111111-2222-3333-4444-555555555555")
        pendingRow(bob, "66666666-7777-8888-9999-aaaaaaaaaaaa")

        reset("alice")

        assertEquals("sync-api: alice's pending_sealed rows cleared on reset", 0L, countPending(alice))
        assertEquals("sync-api: bob's pending_sealed rows untouched on reset", 1L, countPending(bob))
    }

    @Test
    fun `reset of account with no pending_sealed rows still succeeds`() {
        val alice = register("alice", 1)
        keyUp(alice)
        reset("alice")
        assertEquals("sync-api: no rows, reset still succeeds", 0L, countPending(alice))
    }

    @Test
    fun `reset clear commits with the overwrite (atomic)`() {
        val alice = register("alice", 1)
        keyUp(alice)
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        runSql(temp, forcedClearTrigger)

        val failure = expectForcedFailure {
            reset("alice")
        }
        assertTrue("sync-api: forced failure: ${failure.message}", failure.message.orEmpty().contains("forced_clear"))

        assertEquals("sync-api: rollback kept alice's pending_sealed row", 1L, countPending(alice))

        runSql(temp, "DROP TRIGGER forced_clear_failure")
        reset("alice")
        assertEquals("sync-api: after trigger dropped, alice cleared", 0L, countPending(alice))
    }

    @Test
    fun `storeKeys clears pending_sealed when it stores (changed == 1)`() {
        val alice = register("alice", 1)
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val result = runBlocking { accounts.storeKeys(alice, keys) }
        if (result !is StoreKeysResult.Stored) throw AssertionError("sync-api: storeKeys gave $result")

        assertEquals("sync-api: alice's pending_sealed rows cleared on storeKeys", 0L, countPending(alice))
    }

    @Test
    fun `storeKeys on a ResetPending account does not clear (guard)`() {
        val alice = register("alice", 1)
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET reset_pending = 1 WHERE id = ?").use { statement ->
                    statement.setLong(1, alice)
                    statement.executeUpdate()
                }
            }
        }

        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val result = runBlocking { accounts.storeKeys(alice, keys) }
        if (result !is StoreKeysResult.ResetPending) throw AssertionError("sync-api: storeKeys on pending gave $result")

        assertEquals("sync-api: pending account's rows kept (guard refused)", 1L, countPending(alice))
    }

    @Test
    fun `storeKeys when nothing changed does not clear (guard)`() {
        val alice = register("alice", 1)
        keyUp(alice)
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val result = runBlocking { accounts.storeKeys(alice, keys) }
        if (result !is StoreKeysResult.AlreadyKeyed) throw AssertionError("sync-api: second storeKeys gave $result")

        assertEquals("sync-api: no-change storeKeys kept rows", 1L, countPending(alice))
    }

    @Test
    fun `storeKeys clear commits with the credential write (atomic)`() {
        val alice = register("alice", 1)
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        runSql(temp, forcedClearTrigger)

        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val failure = expectForcedFailure {
            runBlocking { accounts.storeKeys(alice, keys) }
        }
        assertTrue("sync-api: forced failure: ${failure.message}", failure.message.orEmpty().contains("forced_clear"))

        assertEquals("sync-api: rollback kept pending_sealed row", 1L, countPending(alice))

        runSql(temp, "DROP TRIGGER forced_clear_failure")
        val result = runBlocking { accounts.storeKeys(alice, keys) }
        if (result !is StoreKeysResult.Stored) throw AssertionError("sync-api: storeKeys after trigger gave $result")
        assertEquals("sync-api: after trigger dropped, row cleared", 0L, countPending(alice))
    }

    @Test
    fun `storeKeys leaves another account's pending_sealed rows`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        pendingRow(bob, "66666666-7777-8888-9999-aaaaaaaaaaaa")

        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val result = runBlocking { accounts.storeKeys(alice, keys) }
        if (result !is StoreKeysResult.Stored) throw AssertionError("sync-api: storeKeys gave $result")

        assertEquals("sync-api: alice's rows cleared", 0L, countPending(alice))
        assertEquals("sync-api: bob's rows untouched", 1L, countPending(bob))
    }
}
