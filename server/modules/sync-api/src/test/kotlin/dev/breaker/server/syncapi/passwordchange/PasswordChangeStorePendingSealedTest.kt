package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TestDatabases
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A password change replaces the box key, so the rows sealed to the old key go with it, in the same transaction. */
internal class PasswordChangeStorePendingSealedTest : PasswordChangeStoreTestBase() {

    private val forcedClearTrigger =
        "CREATE TRIGGER forced_clear_failure AFTER DELETE ON pending_sealed " +
            "BEGIN SELECT RAISE(ABORT, 'forced_clear'); END"

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

    @Test
    fun `a password change clears the account's own pending_sealed rows and leaves another account's`() {
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        pendingRow(alice, "11111111-2222-3333-4444-555555555555")
        pendingRow(bob, "66666666-7777-8888-9999-aaaaaaaaaaaa")

        changed(change(alice, session.tokenId, newRequest()))

        assertEquals("sync-api: alice's rows cleared by the change", 0L, countPending(alice))
        assertEquals("sync-api: bob's rows untouched by the change", 1L, countPending(bob))
    }

    @Test
    fun `a password change of an account with no pending_sealed rows still succeeds`() {
        changed(change(alice, session.tokenId, newRequest()))
        assertEquals("sync-api: nothing to clear", 0L, countPending(alice))
    }

    @Test
    fun `a refused password change keeps the pending_sealed rows`() {
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        val result = change(alice, session.tokenId, newRequest(keyVersion = 5))

        assertTrue("sync-api: expected a key version refusal but got $result", result is PasswordChangeResult.KeyVersionMismatch)
        assertEquals("sync-api: a refused change clears nothing", 1L, countPending(alice))
    }

    @Test
    fun `a failure of the clear rolls the whole password change back`() {
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        runSql(temp, forcedClearTrigger)

        val failure = TestDatabases.expectFailure<SQLException>("the change under a forced clear failure") {
            change(alice, session.tokenId, newRequest())
        }

        assertTrue("sync-api: the failure must be the forced one: ${failure.message}", failure.message.orEmpty().contains("forced_clear"))
        assertEquals("sync-api: the row is still there", 1L, countPending(alice))
        assertEquals("sync-api: the key version did not move", "1", snapshot(alice)[13])

        runSql(temp, "DROP TRIGGER forced_clear_failure")
        assertEquals("sync-api: the same change works once the trigger is gone", 2, changed(change(alice, session.tokenId, newRequest())).keyVersion)
        assertEquals("sync-api: and now the row is cleared", 0L, countPending(alice))
    }
}
