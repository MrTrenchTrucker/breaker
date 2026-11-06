package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.getPage
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.postSync
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.sessionToken
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ReaderSyncRoutesPullPurgeTest : TempDatabaseTest() {

    private lateinit var clock: MutableClock
    private lateinit var temp: TempDatabase
    private var accountId = 0L
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private lateinit var token: String

    private fun cursor(since: Long): String = "$accountId-$since"

    @org.junit.Before
    fun openRoutes() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        accountId = TokenFixtures.register(temp, "alice", 1).accountId
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        token = sessionToken(tokenStore, accountId)
    }

    @Test
    fun `purge runs inside the pull after the number is set in the same transaction`() {
        // Arithmetic: push r1 -> change 1; delete r1 -> change 2 (delete record).
        assertEquals("sync-api: push r1 answers 204",
            HttpStatusCode.NoContent, postSync(tokenStore, store, token,
                "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}").status)
        assertEquals("sync-api: delete r1 answers 204",
            HttpStatusCode.NoContent,
            dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.deleteTranscription(
                tokenStore, store, token, CLIENT_ID).status)
        // Pull at since 2: number set to 2 (floor = 2), purge removes delete record at change 2.
        val reply = getPage(tokenStore, store, token, cursor(2))
        assertEquals("sync-api: pull at since 2 answers 200", HttpStatusCode.OK, reply.status)
        // Accept control: the delete record is gone and purged_through = 2 (highest removed).
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no count row" }
                    assertEquals("sync-api: purge removed the delete record", 0L, rows.getLong(1))
                }
            }
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no floor row" }
                    assertEquals("sync-api: purged_through = highest removed = 2", 2L, rows.getLong(1))
                }
            }
        }
    }

    @Test
    fun `a failing purge rolls the number back too`() {
        // Behaviour this test shows: when the purge aborts, the whole pull transaction rolls back,
        // including the session number set just before it - no session claims a point whose purge
        // did not happen. (Companion store-level test ReaderTranscriptionPurgeRecordsTest shows the
        // store-level fact: purged_through stays unset and the delete record is still there.)
        // Arithmetic: push r1 -> change 1; delete r1 -> change 2 (delete record).
        assertEquals("sync-api: push r1 answers 204",
            HttpStatusCode.NoContent, postSync(tokenStore, store, token,
                "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}").status)
        assertEquals("sync-api: delete r1 answers 204",
            HttpStatusCode.NoContent,
            dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.deleteTranscription(
                tokenStore, store, token, CLIENT_ID).status)
        // Inject a failure: raising purged_through aborts. The pull is one transaction:
        // the number set just before the purge rolls back as well.
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "CREATE TRIGGER fail_purge_raise BEFORE UPDATE ON accounts " +
                    "WHEN NEW.purged_through > OLD.purged_through BEGIN SELECT RAISE(ABORT, 'forced'); END"
            ).use { it.executeUpdate() }
        }
        val reply = getPage(tokenStore, store, token, cursor(2))
        assertEquals("sync-api: failing purge answers 503", HttpStatusCode.ServiceUnavailable, reply.status)
        // Accept control: nothing changed - number unset, delete record still there.
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "SELECT last_pulled_change FROM tokens WHERE account_id = ? AND kind = 'session'"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no token row" }
                    val value = rows.getLong(1)
                    assertNull("sync-api: rolled back, last_pulled_change is NULL",
                        if (rows.wasNull()) null else value)
                }
            }
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no count row" }
                    assertEquals("sync-api: delete record still present", 1L, rows.getLong(1))
                }
            }
        }
    }

    @Test
    fun `a second pull at a higher since purges more records only above the old floor`() {
        // Arithmetic: push r1 (c1), push r2 (c2), delete r1 (c3), delete r2 (c4).
        assertEquals("sync-api: push r1 answers 204",
            HttpStatusCode.NoContent, postSync(tokenStore, store, token,
                "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}").status)
        val otherId = "1b0d" + "5e2f-7b3c-4d4e-8f9a-" + "2b3c4d" + "5e6f70"
        assertEquals("sync-api: push r2 answers 204",
            HttpStatusCode.NoContent, postSync(tokenStore, store, token,
                "{\"client_id\":\"$otherId\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}").status)
        assertEquals("sync-api: delete r1 answers 204",
            HttpStatusCode.NoContent,
            dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.deleteTranscription(
                tokenStore, store, token, CLIENT_ID).status)
        assertEquals("sync-api: delete r2 answers 204",
            HttpStatusCode.NoContent,
            dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.deleteTranscription(
                tokenStore, store, token, otherId).status)
        // Pull at since 3: number = 3, floor = 3, purge removes delete record at c3 only.
        assertEquals("sync-api: pull at since 3 answers 200",
            HttpStatusCode.OK, getPage(tokenStore, store, token, cursor(3)).status)
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no floor row" }
                    assertEquals("sync-api: first purge raises floor to 3", 3L, rows.getLong(1))
                }
            }
            // Accept control: exactly one delete record is left (the one at c4; c3 was removed).
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no count row" }
                    assertEquals("sync-api: one delete record left after first pull", 1L, rows.getLong(1))
                }
            }
        }
        // Pull at since 4: number = 4, floor = 4, purge removes delete record at c4.
        assertEquals("sync-api: pull at since 4 answers 200",
            HttpStatusCode.OK, getPage(tokenStore, store, token, cursor(4)).status)
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no floor row" }
                    assertEquals("sync-api: second purge raises floor to 4", 4L, rows.getLong(1))
                }
            }
            // Both delete records are now removed (change 3 by the first pull, change 4 by the second).
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no count row" }
                    assertEquals("sync-api: both delete records removed", 0L, rows.getLong(1))
                }
            }
        }
    }
}
