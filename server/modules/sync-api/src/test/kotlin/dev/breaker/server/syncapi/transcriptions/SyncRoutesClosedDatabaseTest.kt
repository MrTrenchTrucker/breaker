package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

// The transcription store's database is closed, and the token store's database stays open, so a 503
// can only come from the store. A store failure is never a 500: both routes answer 503 with the
// fixed body, and the exception text reaches no body.
internal class SyncRoutesClosedDatabaseTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var token: String = ""

    @Before
    fun setUp() {
        temp = openTemp()
        val clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        val alice = TokenFixtures.register(temp, "alice", 1)
        token = SyncRouteTestSupport.sessionToken(tokenStore, alice.accountId)
        val closed = openTemp()
        closed.db.close()
        store = SyncRouteTestSupport.newStore(closed, clock)
    }

    @Test
    fun `a push on a closed database answers 503 with the fixed body`() {
        val reply = SyncRouteTestSupport.postSync(
            tokenStore, store, token, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED),
        )
        assertEquals("sync-api: closed database push status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: closed database push body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
        assertFalse("sync-api: no exception text in the push body", reply.body.contains("the database is closed"))
    }

    @Test
    fun `a list on a closed database answers 503 with the fixed body`() {
        val reply = SyncRouteTestSupport.getList(tokenStore, store, token)
        assertEquals("sync-api: closed database list status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: closed database list body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
        assertFalse("sync-api: no exception text in the list body", reply.body.contains("the database is closed"))
    }
}
