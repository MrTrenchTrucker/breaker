package dev.breaker.server.syncapi.transcriptions

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

// The token store's database is closed, and the transcription store's database stays open, so a 503
// can only come from the session check. A token-store failure is never a 401 or a 500: both routes answer
// 503 with the fixed body, and the exception text reaches no body.
internal class SyncRoutesTokenStoreClosedTest : TempDatabaseTest() {

    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var token: String = ""

    @Before
    fun setUp() {
        val tokenTemp = openTemp()
        val clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(tokenTemp, clock)
        val alice = TokenFixtures.register(tokenTemp, "alice", 1)
        token = SyncRouteTestSupport.sessionToken(tokenStore, alice.accountId)
        tokenTemp.db.close()
        val temp = openTemp()
        store = SyncRouteTestSupport.newStore(temp, clock)
    }

    @Test
    fun `a push with a closed token store answers 503 with the fixed body`() {
        val reply = SyncRouteTestSupport.postSync(
            tokenStore, store, token, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED),
        )
        assertEquals("sync-api: closed token store push status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: closed token store push body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
        assertFalse("sync-api: no exception text in the push body", reply.body.contains("the database is closed"))
    }

    @Test
    fun `a list with a closed token store answers 503 with the fixed body`() {
        val reply = SyncRouteTestSupport.getList(tokenStore, store, token)
        assertEquals("sync-api: closed token store list status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: closed token store list body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
        assertFalse("sync-api: no exception text in the list body", reply.body.contains("the database is closed"))
    }
}
