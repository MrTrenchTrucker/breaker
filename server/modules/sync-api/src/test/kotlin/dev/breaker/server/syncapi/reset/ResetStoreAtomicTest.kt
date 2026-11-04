package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.readKeyColumns
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TestDatabases
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reset is one transaction: a failure at any write leaves every table as it was. */
internal class ResetStoreAtomicTest : ResetStoreTestBase() {

    private val codeInsertTrigger =
        "CREATE TRIGGER forced_reset_failure BEFORE INSERT ON reset_codes " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END"
    private val attemptsDeleteTrigger =
        "CREATE TRIGGER forced_reset_failure BEFORE DELETE ON signin_attempts " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END"

    /** The reset fails under the trigger, every table is as before, and it works once the trigger is dropped. */
    private fun assertAtomicUnder(triggerSql: String) {
        plantCode(alice, 7, clock.millis() + 3_600_000L)
        val before = worldState()
        runSql(temp, triggerSql)

        val failure = TestDatabases.expectFailure<SQLException>("reset under a forced failure") {
            runBlocking { store.resetAccount("alice") }
        }

        assertTrue("sync-api: the failure must be the forced one: ${failure.message}", failure.message.orEmpty().contains("forced"))
        assertEquals("sync-api: a failed reset must leave every table as it was", before, worldState())
        val columns = readKeyColumns(temp, alice)
        assertArrayEquals("sync-api: wrapped_dek after the failure", KeyPatterns.wrappedDek(), columns.wrappedDek)
        assertArrayEquals("sync-api: wrapped_box_privkey after the failure", KeyPatterns.wrappedBoxPrivkey(), columns.wrappedBoxPrivkey)
        assertArrayEquals("sync-api: box_pubkey after the failure", KeyPatterns.boxPubkey(), columns.boxPubkey)
        assertArrayEquals("sync-api: prev_wrapped_dek after the failure", KeyPatterns.prevWrappedDek(), columns.prevWrappedDek)
        assertEquals("sync-api: reset_pending after the failure", "0", col("reset_pending"))
        assertEquals("sync-api: change_seq after the failure", "5", col("change_seq"))
        assertEquals("sync-api: purged_through after the failure", "0", col("purged_through"))
        assertTrue("sync-api: the session was revoked", isLive(aliceSession))
        assertTrue("sync-api: the agent token was revoked", isLive(aliceAgent))
        assertEquals("sync-api: transcriptions were deleted", 5L, num("SELECT count(*) FROM transcriptions WHERE account_id = $alice"))
        assertEquals("sync-api: the wrong tries were cleared", 3L, num("SELECT wrong_count FROM signin_attempts WHERE username_lower = 'alice'"))
        assertEquals("sync-api: the old code row was lost", 1L, num("SELECT count(*) FROM reset_codes WHERE account_id = $alice"))

        runSql(temp, "DROP TRIGGER forced_reset_failure")
        assertEquals("sync-api: the same reset after the trigger is dropped", 43, reset("alice").length)
    }

    @Test
    fun `a forced error on the insert of the new code leaves every table as it was`() {
        assertAtomicUnder(codeInsertTrigger)
    }

    @Test
    fun `a forced error on the delete of the wrong try row leaves every table as it was`() {
        assertAtomicUnder(attemptsDeleteTrigger)
    }
}
