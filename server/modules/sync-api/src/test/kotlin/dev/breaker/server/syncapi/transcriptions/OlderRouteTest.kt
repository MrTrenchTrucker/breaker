package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_CLIENT_ID
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// GET /v1/transcriptions/older through the route: the exact JSON, next, two accounts that never see each
// other, the fixed 400, 401, 403 and 503 bodies, and a read that writes nothing. Stored times are the start
// plus an offset in milliseconds; the expected stored_at texts are literals.
internal class OlderRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var aliceId: Long = 0L
    private var bobId: Long = 0L
    private lateinit var aliceToken: String
    private val base: Long = TokenFixtures.START.toEpochMilli()

    private val fixedBadRequest =
        """{"error":"bad_request","message":"older_than must be a stored time in milliseconds, or that time and a change number joined by a dash"}"""

    @Before
    fun setUp() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        aliceId = TokenFixtures.register(temp, "alice", 1).accountId
        bobId = TokenFixtures.register(temp, "bob", 2).accountId
        aliceToken = SyncRouteTestSupport.sessionToken(tokenStore, aliceId)
    }

    private fun bearer(token: String): String = "Bearer $token"

    private fun get(authorization: String?, olderThan: String?): SyncReply =
        OlderRouteTestSupport.getOlder(tokenStore, store, authorization, olderThan)

    // Stores a live row for [accountId] at [offsetMs] after START.
    private fun pushAt(accountId: Long, offsetMs: Long, clientId: String) {
        clock.set(TokenFixtures.START.plusMillis(offsetMs))
        runBlocking { store.push(accountId, clientId, 0, ENCRYPTED) }
    }

    private fun pageBody(items: String, next: String, more: Boolean): String =
        "{\"items\":[" + items + "],\"next\":\"" + next + "\",\"more\":" + more + "}"

    private fun liveJson(clientId: String, change: Long, storedAt: String): String =
        SyncRouteTestSupport.pageRecordJson(clientId, change, false, 0, ENCRYPTED, storedAt)

    // Fails when next drops the change part: the next call would then start at the wrong row.
    @Test
    fun `a page of one row answers the exact JSON with next and more false`() {
        pushAt(aliceId, 0L, CLIENT_ID)
        val reply = get(bearer(aliceToken), "${base + 1_000L}")
        assertEquals("sync-api: one row status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "sync-api: one row body",
            pageBody(liveJson(CLIENT_ID, 1L, "2023-11-14T22:13:20.000Z"), "$base-1", false),
            reply.body,
        )
    }

    // Fails when next is the time alone, or the first row sent instead of the last one.
    @Test
    fun `the next of a non-empty page is the stored time and change of the oldest row sent`() {
        pushAt(aliceId, 1_000L, CLIENT_ID)
        pushAt(aliceId, 2_000L, OTHER_CLIENT_ID)
        val reply = get(bearer(aliceToken), "${base + 10_000L}")
        assertEquals(
            "sync-api: two rows body",
            pageBody(
                liveJson(OTHER_CLIENT_ID, 2L, "2023-11-14T22:13:22.000Z") + "," +
                    liveJson(CLIENT_ID, 1L, "2023-11-14T22:13:21.000Z"),
                "${base + 1_000L}-1",
                false,
            ),
            reply.body,
        )
    }

    // Fails when the empty page answers the bound text as sent instead of rendered: a leading zero is dropped.
    @Test
    fun `the next of an empty page is the bound with its leading zeros dropped`() {
        val reply = get(bearer(aliceToken), "005-03")
        assertEquals("sync-api: empty page status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: empty page body", pageBody("", "5-3", false), reply.body)
    }

    // Fails when the empty page adds a dash and zero to a bound that has no change part.
    @Test
    fun `the next of an empty page with a bound time alone is that time alone`() {
        val reply = get(bearer(aliceToken), "${base + 10_000L}")
        assertEquals("sync-api: empty page body", pageBody("", "${base + 10_000L}", false), reply.body)
    }

    // Fails when the account filter is dropped: each account would see the other's row.
    @Test
    fun `two accounts never see the rows of each other`() {
        val bobToken = SyncRouteTestSupport.sessionToken(tokenStore, bobId)
        pushAt(aliceId, 1_000L, CLIENT_ID)
        pushAt(bobId, 2_000L, OTHER_CLIENT_ID)
        val alice = get(bearer(aliceToken), "${base + 10_000L}")
        assertEquals(
            "sync-api: alice sees only her row",
            pageBody(liveJson(CLIENT_ID, 1L, "2023-11-14T22:13:21.000Z"), "${base + 1_000L}-1", false),
            alice.body,
        )
        assertFalse("sync-api: alice does not see bob's client id", alice.body.contains(OTHER_CLIENT_ID))
        val bob = get(bearer(bobToken), "${base + 10_000L}")
        assertEquals(
            "sync-api: bob sees only his row",
            pageBody(liveJson(OTHER_CLIENT_ID, 1L, "2023-11-14T22:13:22.000Z"), "${base + 2_000L}-1", false),
            bob.body,
        )
    }

    // Fails when the fixed 400 text changes: the body is the literal from the contract.
    @Test
    fun `a missing older than answers 400 with the fixed body`() {
        val reply = get(bearer(aliceToken), null)
        assertEquals("sync-api: missing bound status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("sync-api: missing bound body", fixedBadRequest, reply.body)
    }

    // Fails when a malformed bound is served: each malformed text answers 400, and a well formed one answers 200.
    @Test
    fun `malformed bounds answer 400 and a well formed bound answers 200`() {
        for (text in listOf("", "-5", "5-", "abc", "5-3-1", "9".repeat(16))) {
            val reply = get(bearer(aliceToken), text)
            assertEquals("sync-api: malformed bound status", HttpStatusCode.BadRequest, reply.status)
            assertEquals("sync-api: malformed bound body", fixedBadRequest, reply.body)
        }
        assertEquals("sync-api: accept control status", HttpStatusCode.OK, get(bearer(aliceToken), "5").status)
    }

    // Fails when a request without a session is served: the session check must answer first.
    @Test
    fun `a request with no token or an unknown token answers 401 with the unauthorized body`() {
        val none = get(null, "5")
        assertEquals("sync-api: no token status", HttpStatusCode.Unauthorized, none.status)
        assertEquals("sync-api: no token body", SyncRouteTestSupport.UNAUTHORIZED_BODY, none.body)
        val unknown = get(bearer(SyncRouteTestSupport.UNKNOWN_TOKEN), "5")
        assertEquals("sync-api: unknown token status", HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("sync-api: unknown token body", SyncRouteTestSupport.UNAUTHORIZED_BODY, unknown.body)
    }

    // Fails when a revoked session is still served: the live rule must drop revoked rows.
    @Test
    fun `a revoked session answers 401 with the unauthorized body`() {
        val issued = SyncRouteTestSupport.issuedSession(tokenStore, aliceId)
        assertTrue("sync-api: the session is revoked", runBlocking { tokenStore.revokeSession(issued.tokenId) })
        val reply = get(bearer(issued.secret.reveal()), "5")
        assertEquals("sync-api: revoked session status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: revoked session body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
    }

    // Fails when an agent token is accepted as a session: the refusal answers 403 with the forbidden body.
    @Test
    fun `an agent token answers 403 with the forbidden body`() {
        val agent = SyncRouteTestSupport.agentToken(tokenStore, aliceId)
        val reply = get(bearer(agent), "5")
        assertEquals("sync-api: agent token status", HttpStatusCode.Forbidden, reply.status)
        assertEquals("sync-api: agent token body", SyncRouteTestSupport.FORBIDDEN_BODY, reply.body)
    }

    // Fails when an unformattable stored time is rendered as text instead of answered 503.
    @Test
    fun `a stored time that cannot be formatted answers 503 with the fixed body`() {
        SyncRouteTestSupport.insertRawRow(temp, aliceId, CLIENT_ID, 0, ENCRYPTED, -1L)
        val reply = get(bearer(aliceToken), "${base + 1_000L}")
        assertEquals("sync-api: unformattable time status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: unformattable time body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
    }

    // Fails when a store failure is answered 200 with an empty page instead of 503.
    @Test
    fun `a closed database answers 503 with the fixed body and no exception text`() {
        val closed = openTemp()
        closed.db.close()
        val closedStore = SyncRouteTestSupport.newStore(closed, clock)
        val reply = OlderRouteTestSupport.getOlder(tokenStore, closedStore, bearer(aliceToken), "5")
        assertEquals("sync-api: closed database status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: closed database body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
        assertFalse("sync-api: no exception text in the body", reply.body.contains("the database is closed"))
    }

    // Fails when the read writes: a purge bump (purged_through) or a session number would show here.
    @Test
    fun `a read writes nothing and leaves the session numbers and the rows as they were`() {
        val first = SyncRouteTestSupport.issuedSession(tokenStore, aliceId)
        val second = SyncRouteTestSupport.issuedSession(tokenStore, aliceId)
        pushAt(aliceId, 1_000L, CLIENT_ID)
        clock.set(TokenFixtures.START.plusMillis(2_000L))
        assertEquals("sync-api: the row is deleted", DeleteResult.Deleted, runBlocking { store.delete(aliceId, CLIENT_ID) })
        // The first session pulls up to change 2. The second has no number, so the purge is held.
        val pulled = SyncRouteTestSupport.getPage(tokenStore, store, first.secret.reveal(), "$aliceId-2")
        assertEquals("sync-api: the first pull is served", HttpStatusCode.OK, pulled.status)
        assertEquals("sync-api: first session number before", 2L, pulledNumber(first.tokenId))
        assertNull("sync-api: second session has no number before", pulledNumber(second.tokenId))
        val reply = get(bearer(second.secret.reveal()), "${base + 10_000L}")
        assertEquals("sync-api: the read is served", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: the read answers an empty page", pageBody("", "${base + 10_000L}", false), reply.body)
        assertNull("sync-api: second session has no number after", pulledNumber(second.tokenId))
        assertEquals("sync-api: first session number after", 2L, pulledNumber(first.tokenId))
        assertEquals("sync-api: purged_through is still 0", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: the delete record is still stored", 1L, rowCountOf(aliceId))
    }

    private fun pulledNumber(tokenId: Long): Long? =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT last_pulled_change FROM tokens WHERE id = ?").use { statement ->
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
            connection.prepareStatement("SELECT COUNT(*) FROM transcriptions WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: count returned no row" }
                    rows.getLong(1)
                }
            }
        }
}
