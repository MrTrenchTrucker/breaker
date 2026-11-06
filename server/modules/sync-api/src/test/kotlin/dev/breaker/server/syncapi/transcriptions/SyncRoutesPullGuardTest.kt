package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CURSOR_INVALID_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.FORBIDDEN_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.GONE_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.UNAVAILABLE_BODY
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// The pull route's guards: a full list, refused cursors, a 410, an agent token and a head above the
// cursor write nothing or store only what they must; a failing purge leaves the number and the rows as
// they were. Every refusal has an accept control, and every no-write test reads the numbers, the
// purged_through and the row count before and after.
internal class SyncRoutesPullGuardTest : TempDatabaseTest() {

    private companion object {
        const val STORED_AT = "2023-11-14T22:13:20Z"
        const val CREATE_TRIGGER_SQL =
            "CREATE TRIGGER forced_purge_failure BEFORE DELETE ON transcriptions " +
                "WHEN OLD.deleted = 1 BEGIN SELECT RAISE(ABORT, 'forced'); END"
        const val DROP_TRIGGER_SQL = "DROP TRIGGER forced_purge_failure"
    }

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var aliceId: Long = 0L
    private var bobId: Long = 0L
    private var aliceFirstId: Long = 0L
    private var aliceSecondId: Long = 0L
    private lateinit var aliceToken: String
    private lateinit var aliceSecondToken: String

    @Before
    fun openWorld() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        aliceId = TokenFixtures.register(temp, "alice", 1).accountId
        bobId = TokenFixtures.register(temp, "bob", 2).accountId
        SyncRouteTestSupport.issuedSession(tokenStore, bobId)
        SyncRouteTestSupport.issuedSession(tokenStore, bobId)
        val aliceFirst = SyncRouteTestSupport.issuedSession(tokenStore, aliceId)
        val aliceSecond = SyncRouteTestSupport.issuedSession(tokenStore, aliceId)
        aliceFirstId = aliceFirst.tokenId
        aliceSecondId = aliceSecond.tokenId
        assertNotEquals("sync-api: setup, alice first id", aliceId, aliceFirstId)
        assertNotEquals("sync-api: setup, alice second id", aliceId, aliceSecondId)
        assertNotEquals("sync-api: setup, the two accounts", aliceId, bobId)
        aliceToken = aliceFirst.secret.reveal()
        aliceSecondToken = aliceSecond.secret.reveal()
    }

    private fun pushRow(token: String, clientId: String, encrypted: String): SyncReply =
        SyncRouteTestSupport.postSync(
            tokenStore, store, token, SyncRouteTestSupport.pushBody(clientId, "0", encrypted),
        )

    private fun deleteRow(token: String, clientId: String): SyncReply =
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, clientId)

    private fun pullSince(token: String, accountId: Long, since: Long): SyncReply =
        SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-$since")

    private fun expectStatus(what: String, expected: HttpStatusCode, reply: SyncReply) =
        assertEquals("sync-api: $what status", expected, reply.status)

    private fun expectBody(what: String, expected: String, reply: SyncReply) =
        assertEquals("sync-api: $what body", expected, reply.body)

    private fun pulledNumber(tokenId: Long): Long? =
        inTransaction(temp) { connection ->
            val sql = "SELECT last_pulled_change FROM tokens WHERE id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row for the number" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    private fun purgedThroughOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no account row for purged_through" }
                    rows.getLong(1)
                }
            }
        }

    private fun rowCountOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            val sql = "SELECT COUNT(*) FROM transcriptions WHERE account_id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: count returned no row" }
                    rows.getLong(1)
                }
            }
        }

    private fun runStatement(sql: String): Int =
        inTransaction(temp) { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }

    @Test
    fun `a full list sets no number and keeps an earlier one`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        pushRow(aliceToken, OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        expectStatus("pull that sets the number", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 1))
        assertEquals("sync-api: the earlier number before the list", 1L, pulledNumber(aliceFirstId))
        assertNull("sync-api: second session has no number before the list", pulledNumber(aliceSecondId))
        assertEquals("sync-api: purged_through before the list", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count before the list", 2L, rowCountOf(aliceId))

        val list = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        expectStatus("full list", HttpStatusCode.OK, list)
        expectBody(
            "full list",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 1, false, 0, ENCRYPTED, STORED_AT) + "," +
                SyncRouteTestSupport.pageRecordJson(
                    OTHER_CLIENT_ID, 2, false, 0, OTHER_ENCRYPTED, STORED_AT,
                ) +
                "],\"next\":\"$aliceId-2\",\"more\":false}",
            list,
        )
        val secondList = SyncRouteTestSupport.getList(tokenStore, store, aliceSecondToken)
        expectStatus("second session full list", HttpStatusCode.OK, secondList)

        assertEquals("sync-api: the earlier number is kept", 1L, pulledNumber(aliceFirstId))
        assertNull("sync-api: the list sets no number on the second session", pulledNumber(aliceSecondId))
        assertEquals("sync-api: purged_through after the list", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count after the list", 2L, rowCountOf(aliceId))
    }

    @Test
    fun `a bad cursor and a foreign cursor write nothing`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        pushRow(aliceToken, OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        expectStatus("pull that sets the number", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 1))
        assertEquals("sync-api: the number before the refusals", 1L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: purged_through before the refusals", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count before the refusals", 2L, rowCountOf(aliceId))

        val malformed = SyncRouteTestSupport.getPage(tokenStore, store, aliceToken, "abc")
        expectStatus("malformed cursor", HttpStatusCode.BadRequest, malformed)
        expectBody("malformed cursor", CURSOR_INVALID_BODY, malformed)
        val threeParts = SyncRouteTestSupport.getPage(tokenStore, store, aliceToken, "$aliceId-2-3")
        expectStatus("three-part cursor", HttpStatusCode.BadRequest, threeParts)
        expectBody("three-part cursor", CURSOR_INVALID_BODY, threeParts)
        val foreign = pullSince(aliceToken, bobId, 2)
        expectStatus("foreign cursor", HttpStatusCode.BadRequest, foreign)
        expectBody("foreign cursor", CURSOR_INVALID_BODY, foreign)

        assertEquals("sync-api: the number after the refusals", 1L, pulledNumber(aliceFirstId))
        assertNull("sync-api: second session has no number after the refusals", pulledNumber(aliceSecondId))
        assertEquals("sync-api: purged_through after the refusals", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count after the refusals", 2L, rowCountOf(aliceId))

        expectStatus("accept control", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        assertEquals("sync-api: accept control moves the number", 2L, pulledNumber(aliceFirstId))
    }

    @Test
    fun `a 410 writes nothing`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        expectStatus("first session pull", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        expectStatus("second session pull", HttpStatusCode.OK, pullSince(aliceSecondToken, aliceId, 2))
        assertEquals("sync-api: first session number before the 410", 2L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: second session number before the 410", 2L, pulledNumber(aliceSecondId))
        assertEquals("sync-api: purged_through before the 410", 2L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count before the 410", 0L, rowCountOf(aliceId))

        val gone = pullSince(aliceToken, aliceId, 1)
        expectStatus("a 410", HttpStatusCode.Gone, gone)
        expectBody("a 410", GONE_BODY, gone)

        assertEquals("sync-api: first session number after the 410", 2L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: second session number after the 410", 2L, pulledNumber(aliceSecondId))
        assertEquals("sync-api: purged_through after the 410", 2L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count after the 410", 0L, rowCountOf(aliceId))

        val accepted = pullSince(aliceToken, aliceId, 2)
        expectStatus("accept control", HttpStatusCode.OK, accepted)
        expectBody("accept control", "{\"items\":[],\"next\":\"$aliceId-2\",\"more\":false}", accepted)
    }

    @Test
    fun `an agent token is refused with 403 and no session number moves`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        pushRow(aliceToken, OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        expectStatus("pull that sets the number", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 1))
        assertEquals("sync-api: the number before the agent request", 1L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: purged_through before the agent request", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count before the agent request", 2L, rowCountOf(aliceId))

        val agentToken = SyncRouteTestSupport.issuedAgent(tokenStore, aliceId).secret.reveal()
        val refused = SyncRouteTestSupport.getPage(tokenStore, store, agentToken, "$aliceId-2")
        expectStatus("agent token", HttpStatusCode.Forbidden, refused)
        expectBody("agent token", FORBIDDEN_BODY, refused)

        assertEquals("sync-api: the number after the agent request", 1L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: purged_through after the agent request", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count after the agent request", 2L, rowCountOf(aliceId))

        expectStatus("accept control", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        assertEquals("sync-api: accept control moves the number", 2L, pulledNumber(aliceFirstId))
    }

    @Test
    fun `a since above the head stores the head and answers an empty page`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        pushRow(aliceToken, OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        assertNull("sync-api: no number before the request", pulledNumber(aliceFirstId))
        assertEquals("sync-api: row count before the request", 2L, rowCountOf(aliceId))

        val reply = pullSince(aliceToken, aliceId, 5)
        expectStatus("above-head pull", HttpStatusCode.OK, reply)
        expectBody("above-head pull", "{\"items\":[],\"next\":\"$aliceId-2\",\"more\":false}", reply)
        assertEquals("sync-api: the head is stored, not the cursor", 2L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: purged_through after the request", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count after the request", 2L, rowCountOf(aliceId))
    }

    @Test
    fun `a failing purge answers 503 with the fixed body and leaves the number and the rows unchanged`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        expectStatus("second session pull", HttpStatusCode.OK, pullSince(aliceSecondToken, aliceId, 2))
        assertNull("sync-api: first session has no number before", pulledNumber(aliceFirstId))
        assertEquals("sync-api: second session number before", 2L, pulledNumber(aliceSecondId))
        assertEquals("sync-api: purged_through before the failing pull", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: row count before the failing pull", 1L, rowCountOf(aliceId))

        runStatement(CREATE_TRIGGER_SQL)
        try {
            val refused = pullSince(aliceToken, aliceId, 2)
            expectStatus("failing purge", HttpStatusCode.ServiceUnavailable, refused)
            expectBody("failing purge", UNAVAILABLE_BODY, refused)
            assertNull("sync-api: the number is rolled back with the purge", pulledNumber(aliceFirstId))
            assertEquals("sync-api: second session number after", 2L, pulledNumber(aliceSecondId))
            assertEquals("sync-api: purged_through after the failing pull", 0L, purgedThroughOf(aliceId))
            assertEquals("sync-api: row count after the failing pull", 1L, rowCountOf(aliceId))
        } finally {
            runStatement(DROP_TRIGGER_SQL)
        }

        val accepted = pullSince(aliceToken, aliceId, 2)
        expectStatus("accept control", HttpStatusCode.OK, accepted)
        assertEquals("sync-api: accept control moves the number", 2L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: accept control purges", 2L, purgedThroughOf(aliceId))
        assertEquals("sync-api: accept control removes the record", 0L, rowCountOf(aliceId))
    }
}
