package dev.breaker.server.syncapi.sync

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The push route on a good body: status, the stored row, retries and the stored text. */
internal class SyncPushGoodPathTest : SyncRouteTestBase() {

    @Before
    fun setUp() {
        openSyncStores()
    }

    @Test
    fun `a good push answers 204 with no body and the pull shows the row`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val push = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", validEncrypted())))
            assertEquals("push status", HttpStatusCode.NoContent, push.status)
            assertEquals("a 204 carries no body", "", push.bodyAsText())
            val pull = client.pullSync(auth)
            assertEquals("pull status", HttpStatusCode.OK, pull.status)
            assertTrue("the pull shows the pushed client id", pull.bodyAsText().contains("\"client_id\":\"$CLIENT_ID\""))
        }
    }

    @Test
    fun `a retry of the same pair answers 204 and leaves one row`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        val body = utf8(pushBody(CLIENT_ID, "0", validEncrypted()))
        testApplication {
            application { installSyncRoutes() }
            assertEquals("first push", HttpStatusCode.NoContent, client.postSync(auth, body).status)
            assertEquals("retry", HttpStatusCode.NoContent, client.postSync(auth, body).status)
            val pulled = client.pullSync(auth).bodyAsText()
            assertEquals("one row after the retry", 1, pulled.split("\"client_id\"").size - 1)
        }
    }

    @Test
    fun `a repeated pair with different text answers 204 and keeps the first text`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        val first = validEncrypted("first-text")
        val second = validEncrypted("second-text")
        testApplication {
            application { installSyncRoutes() }
            assertEquals("first push", HttpStatusCode.NoContent, client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", first))).status)
            assertEquals("second push", HttpStatusCode.NoContent, client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", second))).status)
            val pulled = client.pullSync(auth).bodyAsText()
            assertTrue("the first text is kept", pulled.contains("\"encrypted\":\"$first\""))
            assertFalse("the second text is not stored", pulled.contains(second))
        }
    }

    @Test
    fun `the stored text is the encrypted string exactly as it was sent`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        // Spaces, a reordered key and a different key order: the same body, not in canonical form.
        val spelled = encryptedOf(
            """{ "v" : 1, "text": "hi", "source": "local", "model": "base.en", "duration_ms": 1500, """ +
                """"created_at": "2023-11-14T22:13:20.000Z" }""",
        )
        testApplication {
            application { installSyncRoutes() }
            val push = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", spelled)))
            assertEquals("push status", HttpStatusCode.NoContent, push.status)
            val pulled = client.pullSync(auth).bodyAsText()
            assertTrue("the stored text is the sent text", pulled.contains("\"encrypted\":\"$spelled\""))
        }
    }

    @Test
    fun `a key version zero push is stored with key version zero`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", validEncrypted())))
            val pulled = client.pullSync(auth).bodyAsText()
            assertTrue("key version zero is stored", pulled.contains("\"key_version\":0"))
        }
    }
}
