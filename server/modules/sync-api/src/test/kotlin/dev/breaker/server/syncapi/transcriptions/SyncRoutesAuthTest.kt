package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Both routes take a user session token only: no token and a bad token are 401, an agent token is 403.
internal class SyncRoutesAuthTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var accountId: Long = 0L

    @Before
    fun setUp() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        accountId = TokenFixtures.register(temp, "alice", 1).accountId
    }

    private fun validBody(): String = SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED)

    private fun storedCount(): Int = runBlocking { store.list(accountId) }.size

    @Test
    fun `a push with no token answers 401 with the session message`() {
        val reply = SyncRouteTestSupport.postSync(tokenStore, store, null, validBody())
        assertEquals("sync-api: push without a token status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: push without a token body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
        assertEquals("sync-api: a refused push stores nothing", 0, storedCount())
    }

    @Test
    fun `a list with no token answers 401 with the session message`() {
        val reply = SyncRouteTestSupport.getList(tokenStore, store, null)
        assertEquals("sync-api: list without a token status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: list without a token body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
    }

    @Test
    fun `a push with an unknown token answers 401`() {
        val reply = SyncRouteTestSupport.postSync(tokenStore, store, SyncRouteTestSupport.UNKNOWN_TOKEN, validBody())
        assertEquals("sync-api: unknown token push status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: unknown token push body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
        assertEquals("sync-api: a refused push stores nothing", 0, storedCount())
    }

    @Test
    fun `a list with an unknown token answers 401`() {
        val reply = SyncRouteTestSupport.getList(tokenStore, store, SyncRouteTestSupport.UNKNOWN_TOKEN)
        assertEquals("sync-api: unknown token list status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: unknown token list body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
    }

    @Test
    fun `a push with a malformed token answers 401`() {
        val reply = SyncRouteTestSupport.postSync(tokenStore, store, "abc", validBody())
        assertEquals("sync-api: malformed token push status", HttpStatusCode.Unauthorized, reply.status)
    }

    @Test
    fun `a push with an agent token answers 403 and stores nothing`() {
        val agent = SyncRouteTestSupport.agentToken(tokenStore, accountId)
        val reply = SyncRouteTestSupport.postSync(tokenStore, store, agent, validBody())
        assertEquals("sync-api: agent push status", HttpStatusCode.Forbidden, reply.status)
        assertEquals("sync-api: agent push body", SyncRouteTestSupport.FORBIDDEN_BODY, reply.body)
        assertEquals("sync-api: a refused agent push stores nothing", 0, storedCount())
    }

    @Test
    fun `a list with an agent token answers 403`() {
        val agent = SyncRouteTestSupport.agentToken(tokenStore, accountId)
        val reply = SyncRouteTestSupport.getList(tokenStore, store, agent)
        assertEquals("sync-api: agent list status", HttpStatusCode.Forbidden, reply.status)
        assertEquals("sync-api: agent list body", SyncRouteTestSupport.FORBIDDEN_BODY, reply.body)
    }

    @Test
    fun `a session token is accepted on both routes as the control`() {
        val session = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
        val pushed = SyncRouteTestSupport.postSync(tokenStore, store, session, validBody())
        assertEquals("sync-api: control push status", HttpStatusCode.NoContent, pushed.status)
        val listed = SyncRouteTestSupport.getList(tokenStore, store, session)
        assertEquals("sync-api: control list status", HttpStatusCode.OK, listed.status)
    }

    // The scheme must be Bearer: a Basic header that carries a live session token is no token at all.
    @Test
    fun `a push with a Basic scheme carrying a live session token answers 401 and stores nothing`() {
        val session = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
        val reply = SyncRouteTestSupport.postSyncWithAuthorization(tokenStore, store, "Basic $session", validBody())
        assertEquals("sync-api: basic scheme push status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: basic scheme push body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
        assertEquals("sync-api: a refused basic push stores nothing", 0, storedCount())
    }

    @Test
    fun `a list with a Basic scheme carrying a live session token answers 401`() {
        val session = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
        val reply = SyncRouteTestSupport.getListWithAuthorization(tokenStore, store, "Basic $session")
        assertEquals("sync-api: basic scheme list status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: basic scheme list body", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
    }

    // A revoked session is dead at once: the revoke is checked by the live-token query, not by the caller.
    @Test
    fun `a push and a list with a revoked session token answer 401`() {
        val issued = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val revoked = runBlocking { tokenStore.revokeSession(issued.tokenId) }
        assertTrue("sync-api: the session must be revoked before the request", revoked)
        val pushed = SyncRouteTestSupport.postSync(tokenStore, store, issued.secret.reveal(), validBody())
        assertEquals("sync-api: revoked session push status", HttpStatusCode.Unauthorized, pushed.status)
        assertEquals("sync-api: revoked session push body", SyncRouteTestSupport.UNAUTHORIZED_BODY, pushed.body)
        val listed = SyncRouteTestSupport.getList(tokenStore, store, issued.secret.reveal())
        assertEquals("sync-api: revoked session list status", HttpStatusCode.Unauthorized, listed.status)
        assertEquals("sync-api: revoked session list body", SyncRouteTestSupport.UNAUTHORIZED_BODY, listed.body)
    }

    // A session is dead at its expiry instant: issued at the fixed start, it expires 30 days later, so a clock
    // moved 31 days on is past it.
    @Test
    fun `a push and a list with a session token past its idle lifetime answer 401`() {
        val session = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
        clock.advance(Duration.ofDays(31))
        val pushed = SyncRouteTestSupport.postSync(tokenStore, store, session, validBody())
        assertEquals("sync-api: expired session push status", HttpStatusCode.Unauthorized, pushed.status)
        assertEquals("sync-api: expired session push body", SyncRouteTestSupport.UNAUTHORIZED_BODY, pushed.body)
        val listed = SyncRouteTestSupport.getList(tokenStore, store, session)
        assertEquals("sync-api: expired session list status", HttpStatusCode.Unauthorized, listed.status)
        assertEquals("sync-api: expired session list body", SyncRouteTestSupport.UNAUTHORIZED_BODY, listed.body)
    }

    // A revoked agent token resolves to nothing, so it is 401 and not the 403 of a live agent token.
    @Test
    fun `a push and a list with a revoked agent token answer 401 and not 403`() {
        val issued = SyncRouteTestSupport.issuedAgent(tokenStore, accountId)
        val revoked = runBlocking { tokenStore.revokeAgentToken(issued.tokenId) }
        assertTrue("sync-api: the agent token must be revoked before the request", revoked)
        val pushed = SyncRouteTestSupport.postSync(tokenStore, store, issued.secret.reveal(), validBody())
        assertEquals("sync-api: revoked agent push status", HttpStatusCode.Unauthorized, pushed.status)
        assertEquals("sync-api: revoked agent push body", SyncRouteTestSupport.UNAUTHORIZED_BODY, pushed.body)
        val listed = SyncRouteTestSupport.getList(tokenStore, store, issued.secret.reveal())
        assertEquals("sync-api: revoked agent list status", HttpStatusCode.Unauthorized, listed.status)
        assertEquals("sync-api: revoked agent list body", SyncRouteTestSupport.UNAUTHORIZED_BODY, listed.body)
    }
}
