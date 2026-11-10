package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// GET /v1/transcriptions route tests: exact 200 JSON for live and delete records, empty page,
// 400 for every malformed cursor shape, 401 for no/malformed/unknown Bearer (the sign-in-code case is in the hand-off tests), 403 agent,
// 410 gone, 503 on a store failure. Client ids built from short pieces (no hex run of 7+).

internal class SyncRoutesFeedRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var accountId: Long = 0L
    private var token: String = ""

    @Before
    fun openRoutes() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        accountId = TokenFixtures.register(temp, "alice", 1).accountId
        token = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
    }

    private fun pushLive(clientId: String = CLIENT_ID, keyVersion: Int = 0, encrypted: String = ENCRYPTED) {
        SyncRouteTestSupport.postSync(tokenStore, store, token, SyncRouteTestSupport.pushBody(clientId, keyVersion.toString(), encrypted))
    }

    @Test
    fun liveRecordAnswers200WithExactBody() {
        pushLive()
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, null)
        assertEquals("live record status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "live record body",
            "{\"items\":[" + SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 1, false, 0, ENCRYPTED, "2023-11-14T22:13:20.000Z") + "],\"next\":\"$accountId-1\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun deleteRecordAnswers200WithDeleteShape() {
        pushLive()
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CLIENT_ID)
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-1")
        assertEquals("delete record status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "delete record body",
            "{\"items\":[" + SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 2, true, null, null, "2023-11-14T22:13:20.000Z") + "],\"next\":\"$accountId-2\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun emptyPageAnswers200WithEmptyItems() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, null)
        assertEquals("empty page status", HttpStatusCode.OK, reply.status)
        assertEquals("empty page body", "{\"items\":[],\"next\":\"$accountId-0\",\"more\":false}", reply.body)
    }

    @Test
    fun malformedCursorAbcAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "abc")
        assertEquals("abc cursor status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("abc cursor body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun cursorOneMinusAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "1-")
        assertEquals("1- cursor status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("1- cursor body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun cursorMinusOneAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "-1")
        assertEquals("-1 cursor status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("-1 cursor body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun cursorThreePartsAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "1-3-3")
        assertEquals("1-3-3 cursor status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("1-3-3 cursor body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun emptySinceValueAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "")
        assertEquals("empty since status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("empty since body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun anotherAccountTagAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "9-0")
        assertEquals("another account tag status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("another account tag body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun twentyDigitChangePartAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-" + "12345" + "67890" + "12345" + "67890")
        assertEquals("20-digit change status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("20-digit change body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun leadingZeroInAccountPartAnswers400() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "0$accountId-1")
        assertEquals("leading zero status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("leading zero body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun overflowChangePartAnswers400() {
        // 19 nines fits the cursor pattern but overflows a Long: the fit-a-Long check must 400.
        val nines = "99999" + "99999" + "99999" + "9999"
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-$nines")
        assertEquals("overflow change status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("overflow change body", SyncRouteTestSupport.CURSOR_INVALID_BODY, reply.body)
    }

    @Test
    fun noTokenAnswers401() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, null, null)
        assertEquals("no token status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("no token body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
    }

    @Test
    fun malformedBearerAnswers401() {
        val reply = SyncRouteTestSupport.getPageWithAuthorization(tokenStore, store, "Bearer", null)
        assertEquals("malformed Bearer status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("malformed Bearer body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
    }

    @Test
    fun unknownTokenAnswers401() {
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, SyncRouteTestSupport.UNKNOWN_TOKEN, null)
        assertEquals("unknown token status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("unknown token body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
    }

    @Test
    fun agentTokenAnswers403() {
        val agentToken = SyncRouteTestSupport.agentToken(tokenStore, accountId)
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, agentToken, null)
        assertEquals("agent token status", HttpStatusCode.Forbidden, reply.status)
        assertEquals("agent token body", SyncRouteTestSupport.FORBIDDEN_BODY, reply.body)
    }

    @Test
    fun goneCursorAnswers410() {
        SyncRouteTestSupport.insertRawRow(temp, accountId, CLIENT_ID, 0, ENCRYPTED, TokenFixtures.START.toEpochMilli())
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET purged_through = 1 WHERE id = ?").use { s ->
                    s.setLong(1, accountId)
                    s.executeUpdate()
                }
            }
        }
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-0")
        assertEquals("gone cursor status", HttpStatusCode.Gone, reply.status)
        assertEquals("gone cursor body", SyncRouteTestSupport.GONE_BODY, reply.body)
    }

    @Test
    fun closedStoreAnswers503() {
        val closed = openTemp()
        closed.db.close()
        val closedStore = SyncRouteTestSupport.newStore(closed, clock)
        val reply = SyncRouteTestSupport.getPage(tokenStore, closedStore, token, null)
        assertEquals("closed store status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("closed store body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
    }
}
