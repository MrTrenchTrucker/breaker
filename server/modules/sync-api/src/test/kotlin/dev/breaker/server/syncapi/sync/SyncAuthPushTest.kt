package dev.breaker.server.syncapi.sync

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The push route's authentication: 401 for a missing, unknown, expired or revoked token, 403 for an agent token. */
internal class SyncAuthPushTest : SyncRouteTestBase() {

    private val body = utf8(pushBody(CLIENT_ID, "0", validEncrypted()))

    @Before
    fun setUp() {
        openSyncStores()
    }

    @Test
    fun `a push without a token answers 401 with the session body`() = runBlocking<Unit> {
        account("alice", 1)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(null, body)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_SESSION_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a push with a garbage token answers 401`() = runBlocking<Unit> {
        account("alice", 1)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(bearer("A".repeat(43)), body)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_SESSION_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a push with a Basic scheme answers 401`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val token = issueSession(alice)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync("Basic ${token.secret.reveal()}", body)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a push with a revoked session answers 401`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val token = issueSession(alice)
        revoke(token)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(bearer(token.secret.reveal()), body)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_SESSION_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a push with an expired session answers 401`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val token = issueSession(alice)
        expire(token)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(bearer(token.secret.reveal()), body)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `a push with an agent token answers 403 with the agent body`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val agent = issueAgent(alice)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(bearer(agent.secret.reveal()), body)
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_AGENT_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `the 401 and 403 push bodies are the pinned strings`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val agent = issueAgent(alice)
        testApplication {
            application { installSyncRoutes() }
            val missing = client.postSync(null, body)
            val wrongKind = client.postSync(bearer(agent.secret.reveal()), body)
            assertEquals("401 body", UNAUTHORIZED_SESSION_BODY, missing.bodyAsText())
            assertEquals("403 body", FORBIDDEN_AGENT_BODY, wrongKind.bodyAsText())
        }
    }

    @Test
    fun `a lowercase bearer scheme is accepted on the push and on the pull`() = runBlocking<Unit> {
        val token = issueSession(account("alice", 1)).secret.reveal()
        testApplication {
            application { installSyncRoutes() }
            val push = client.postSync("bearer $token", body)
            assertEquals("push status", HttpStatusCode.NoContent, push.status)
            val pull = client.pullSync("bearer $token")
            assertEquals("pull status", HttpStatusCode.OK, pull.status)
        }
    }
}
