package dev.breaker.server.syncapi.sync

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The pull route's authentication: 401 for a missing, expired or revoked token, 403 for an agent token. */
internal class SyncAuthPullTest : SyncRouteTestBase() {

    @Before
    fun setUp() {
        openSyncStores()
    }

    @Test
    fun `a pull without a token answers 401 with the session body`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        insertRow(alice, CLIENT_ID, 0, "AQID", 1_700_000_000_000L)
        testApplication {
            application { installSyncRoutes() }
            val response = client.pullSync(null)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_SESSION_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a pull with a revoked session answers 401`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val token = issueSession(alice)
        revoke(token)
        testApplication {
            application { installSyncRoutes() }
            val response = client.pullSync(bearer(token.secret.reveal()))
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_SESSION_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a pull with an expired session answers 401`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val token = issueSession(alice)
        expire(token)
        testApplication {
            application { installSyncRoutes() }
            val response = client.pullSync(bearer(token.secret.reveal()))
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `a pull with an agent token answers 403 with the agent body`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val agent = issueAgent(alice)
        testApplication {
            application { installSyncRoutes() }
            val response = client.pullSync(bearer(agent.secret.reveal()))
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_AGENT_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `the 401 and 403 pull bodies are the pinned strings`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val agent = issueAgent(alice)
        testApplication {
            application { installSyncRoutes() }
            val missing = client.pullSync(null)
            val wrongKind = client.pullSync(bearer(agent.secret.reveal()))
            assertEquals("401 body", UNAUTHORIZED_SESSION_BODY, missing.bodyAsText())
            assertEquals("403 body", FORBIDDEN_AGENT_BODY, wrongKind.bodyAsText())
        }
    }
}
