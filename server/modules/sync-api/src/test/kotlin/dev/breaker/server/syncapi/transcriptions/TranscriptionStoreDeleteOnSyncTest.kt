package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.UNAVAILABLE_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.postSync
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.pushBody
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.sessionToken
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.SQLException

// Delete-on-sync: POST /v1/sync deletes the account's pending-sealed row with that client id in the SAME
// db.transaction as the push, on the caller's connection: no separate transaction and no try/catch swallow.
// Coverage: a push that lands deletes the row (stored, replaced, the no-op of a deleted record, an identical
// retry); a refused push never touches it; another account's row and the account's other rows stay; a failing
// delete rolls the push back, at store level and at route level (POST /v1/sync answers the fixed 503).
internal class TranscriptionStoreDeleteOnSyncTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        store = TranscriptionStore(temp.db)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    // ---- store-level helpers (runBlocking against temp.db directly) ----

    private fun pushStore(clientId: String, keyVersion: Int, encrypted: String): PushResult =
        runBlocking { store.push(alice, clientId, keyVersion, encrypted) }

    private fun pendingRow(accountId: Long, id: String) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES (?, ?, ?, ?)",
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

    private fun countPending(accountId: Long, id: String): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT count(*) FROM pending_sealed WHERE account_id = ? AND id = ?").use {
                statement ->
                statement.setLong(1, accountId)
                statement.setString(2, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api: no count row" }
                    rows.getLong(1)
                }
            }
        }
    }

    private fun isStored(accountId: Long, clientId: String): Boolean = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND client_id = ? AND deleted = 0",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, clientId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api: no count row" }
                    rows.getLong(1) == 1L
                }
            }
        }
    }

    private val forcedDeleteTrigger =
        "CREATE TRIGGER forced_delete_failure AFTER DELETE ON pending_sealed " +
            "WHEN OLD.account_id IS NOT NULL BEGIN SELECT RAISE(ABORT, 'forced_delete'); END"

    private fun runSql(sql: String) =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.executeUpdate()
                }
            }
        }

    private fun expectForcedFailure(block: () -> Unit): SQLException =
        try {
            block()
            throw AssertionError("sync-api: expected forced failure but nothing was thrown")
        } catch (e: SQLException) {
            e
        }

    // ---- tests ----

    @Test
    fun `a stored push deletes the pending_sealed row`() {
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("sync-api: first push stores", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one"))
        assertEquals("sync-api: pending_sealed row deleted after stored push", 0L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue("sync-api: transcription row is stored", isStored(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    @Test
    fun `a replaced push deletes the pending_sealed row`() {
        assertEquals("sync-api: first push stores", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one"))
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = 1 WHERE id = ?").use {
                    statement ->
                    statement.setLong(1, alice)
                    statement.executeUpdate()
                }
            }
        }
        // The row is made right before the replacing push: the first push is over and a row made earlier would be gone.
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("sync-api: the pending row is there before the replacing push", 1L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertEquals("sync-api: replace stores with next version", PushResult.Replaced, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 1, "two"))
        assertEquals("sync-api: pending_sealed row deleted after replaced push", 0L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    @Test
    fun `a deleted-row no-op push deletes the pending_sealed row`() {
        assertEquals("sync-api: push stores live row", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "live"))
        runBlocking { store.delete(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") }
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("sync-api: a push of a deleted record without resave is a NoOp", PushResult.NoOp, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "again"))
        assertEquals("sync-api: pending_sealed row deleted after the no-op push", 0L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    @Test
    fun `an identical retried push (live-row NoOp) deletes the pending_sealed row`() {
        assertEquals("sync-api: first push stores", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one"))
        // The row is made after the first push, which would have deleted an earlier one: the retry must delete it.
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("sync-api: the pending row is there before the retry", 1L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertEquals("sync-api: identical retry is a NoOp", PushResult.NoOp, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one-again"))
        assertEquals("sync-api: pending_sealed row deleted after identical NoOp push", 0L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    @Test
    fun `a refused-above-account push leaves the pending_sealed row`() {
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("sync-api: push above account version is refused", PushResult.RefusedAboveAccount, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 1, "x"))
        assertEquals("sync-api: pending_sealed row untouched by refusal", 1L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue("sync-api: nothing stored on refusal", isStored(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") == false)
    }

    @Test
    fun `a refused-lower push leaves the pending_sealed row`() {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = 1 WHERE id = ?").use {
                    statement ->
                    statement.setLong(1, alice)
                    statement.executeUpdate()
                }
            }
        }
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("sync-api: a new id below the account's key version is refused", PushResult.RefusedLower, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "v0"))
        assertEquals("sync-api: pending_sealed row untouched by refusal", 1L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue("sync-api: nothing stored on refusal", isStored(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") == false)
    }

    @Test
    fun `a push with no pending_sealed row still stores`() {
        assertEquals("sync-api: push stores with no pending row", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one"))
        assertEquals("sync-api: no row to delete, still stores", 0L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue("sync-api: transcription row is stored", isStored(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    @Test
    fun `another account's pending row with the same id stays after the push`() {
        pendingRow(bob, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("sync-api: alice's push stores", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one"))
        assertEquals("sync-api: bob's pending_sealed row untouched", 1L, countPending(bob, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    @Test
    fun `other pending rows of the same account stay (only the row with that client id goes)`() {
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        pendingRow(alice, "11111111-2222-3333-4444-555555555555")
        assertEquals("sync-api: push stores", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one"))
        assertEquals("sync-api: the pushed client's row deleted", 0L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertEquals("sync-api: the other row of the same account stays", 1L, countPending(alice, "11111111-2222-3333-4444-555555555555"))
    }

    @Test
    fun `a forced delete failure rolls back the push entirely (store level)`() {
        pendingRow(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        runSql(forcedDeleteTrigger)

        val failure = expectForcedFailure {
            pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one")
        }
        assertTrue("sync-api: forced failure: ${failure.message}", failure.message.orEmpty().contains("forced_delete"))

        assertEquals("sync-api: rollback kept the pending_sealed row", 1L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue("sync-api: rollback did not store the transcription row", isStored(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") == false)

        runSql("DROP TRIGGER forced_delete_failure")
        assertEquals("sync-api: after trigger dropped, push stores", PushResult.Stored, pushStore("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0, "one"))
        assertEquals("sync-api: pending_sealed row deleted after successful push", 0L, countPending(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue("sync-api: transcription row is stored after retry", isStored(alice, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    // ---- route-level test (POST /v1/sync answers the fixed 503 on delete failure) ----

    @Test
    fun `the route answers 503 and rolls back when the delete raises`() {
        val clock = MutableClock(TokenFixtures.START)
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val accountId = alice
        val token = sessionToken(tokenStore, accountId)
        val body = pushBody(CLIENT_ID, "0", ENCRYPTED)

        pendingRow(accountId, CLIENT_ID)
        runSql(forcedDeleteTrigger)

        val reply = postSync(tokenStore, store, token, body)
        assertEquals("sync-api: route answers 503 on delete failure", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: the fixed 503 body", UNAVAILABLE_BODY, reply.body)

        assertEquals("sync-api: rollback kept the pending_sealed row", 1L, countPending(accountId, CLIENT_ID))
        assertTrue("sync-api: rollback did not store the transcription row", isStored(accountId, CLIENT_ID) == false)

        runSql("DROP TRIGGER forced_delete_failure")
        val again = postSync(tokenStore, store, token, body)
        assertEquals("sync-api: after trigger dropped, push answers 204", HttpStatusCode.NoContent, again.status)
        assertEquals("sync-api: pending_sealed row deleted after successful push", 0L, countPending(accountId, CLIENT_ID))
        assertTrue("sync-api: transcription row is stored after retry", isStored(accountId, CLIENT_ID))
    }
}
