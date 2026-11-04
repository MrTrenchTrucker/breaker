package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.readKeyColumns
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TestDatabases
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The change is one transaction: a failure at any write leaves every table as it was. */
internal class PasswordChangeStoreAtomicTest : PasswordChangeStoreTestBase() {

    private val afterTokenRevokeTrigger =
        "CREATE TRIGGER forced_change_failure AFTER UPDATE OF revoked_at_ms ON tokens " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END"
    private val lastWriteTrigger =
        "CREATE TRIGGER forced_change_failure BEFORE DELETE ON signin_attempts " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END"

    /** The change fails under the trigger, every table is as before, and it works once the trigger is dropped. */
    private fun assertAtomicUnder(triggerSql: String) {
        pushOld(alice, clientId(0))
        wrongTries("alice", 2)
        addCodes(alice, 1, 2)
        addCodes(bob, 3)
        val before = fullState()
        runSql(temp, triggerSql)

        val failure = TestDatabases.expectFailure<SQLException>("change under a forced failure") {
            change(alice, session.tokenId, newRequest())
        }

        assertTrue("sync-api: the failure must be the forced one: ${failure.message}", failure.message.orEmpty().contains("forced"))
        assertEquals("sync-api: a failed change must leave every table as it was", before, fullState())
        val columns = readKeyColumns(temp, alice)
        assertArrayEquals("sync-api: wrapped_dek after the failure", KeyPatterns.wrappedDek(), columns.wrappedDek)
        assertEquals("sync-api: key_version after the failure", 1, columns.keyVersion)
        assertNull("sync-api: prev_wrapped_dek after the failure", columns.prevWrappedDek)
        assertNull("sync-api: rekeyed_at after the failure", columns.rekeyedAt)
        assertNull("sync-api: the other session was revoked", revokedAt(otherSession))
        assertEquals("sync-api: the wrong tries were cleared", 2L, wrongCountOf("alice"))
        assertEquals("sync-api: the codes of alice were deleted", 2L, codeCount(alice))
        assertEquals("sync-api: the code of bob was deleted", 1L, codeCount(bob))

        runSql(temp, "DROP TRIGGER forced_change_failure")
        assertEquals("sync-api: the same change after the trigger is dropped", 2, changed(change(alice, session.tokenId, newRequest())).keyVersion)
    }

    @Test
    fun `a forced error after the account update leaves every table as it was`() {
        assertAtomicUnder(afterTokenRevokeTrigger)
    }

    @Test
    fun `a forced error at the last write leaves every table as it was`() {
        assertAtomicUnder(lastWriteTrigger)
    }

    @Test
    fun `two changes with the same key version leave exactly one request in the columns`() {
        pushOld(alice, clientId(0))
        val first = newRequest(shift = 0)
        val second = newRequest(shift = 40)

        val results = runBlocking {
            withTimeout(60_000L) {
                coroutineScope {
                    val one = async(Dispatchers.Default) { store.change(alice, session.tokenId, first) }
                    val two = async(Dispatchers.Default) { store.change(alice, session.tokenId, second) }
                    listOf(one.await(), two.await())
                }
            }
        }

        assertEquals("sync-api: exactly one change wins", 1, results.count { result -> result is PasswordChangeResult.Changed })
        assertEquals(
            "sync-api: the other one is refused for its key_version",
            1,
            results.count { result -> result is PasswordChangeResult.KeyVersionMismatch },
        )
        val winner = if (results[0] is PasswordChangeResult.Changed) first else second
        assertColumns(alice, expectedColumns(winner, AccountFixtures.hex(winner.prevWrappedDek)), "the winner")
    }
}
