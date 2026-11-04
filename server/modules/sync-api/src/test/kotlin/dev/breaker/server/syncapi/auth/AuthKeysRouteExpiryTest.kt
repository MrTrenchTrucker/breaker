package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

internal class AuthKeysRouteExpiryTest : AuthKeysRouteTestBase() {

    @Test
    fun `an expired session gets 401 with the sync body and nothing is written`() {
        val id = register("alice", 1)
        val issued = SyncRouteTestSupport.issuedSession(tokenStore, id)
        val bearer = issued.secret.reveal()
        assertEquals("sync-api: the session must work before it is expired", HttpStatusCode.OK, getList(bearer).status)
        TokenFixtures.expireSession(temp, issued.tokenId)
        val reply = postKeys(bearer, validBody())
        assertEquals("sync-api: status of the post with an expired session", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: body of the post with an expired session", SyncRouteTestSupport.UNAUTHORIZED_BODY, reply.body)
        assertKeyless(id, "a post with an expired session")
    }
}
