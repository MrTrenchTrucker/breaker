package dev.breaker.server.syncapi.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The raw body limit, the decoded text limit, and the order of the limit against authentication. */
internal class SyncPushSizeTest : SyncRouteTestBase() {

    private val rawLimit = 131072
    private val decodedLimit = 65536

    @Before
    fun setUp() {
        openSyncStores()
    }

    /** A push whose JSON body is padded with spaces to exactly [size] bytes. */
    private fun paddedPush(size: Int): ByteArray {
        val json = pushBody(CLIENT_ID, "0", validEncrypted())
        return utf8(json + " ".repeat(size - json.length))
    }

    /** The same push body sent as a channel, so the request has no declared length. */
    private suspend fun HttpClient.postWithoutLength(authorization: String, body: ByteArray): HttpResponse =
        post(SYNC_PATH) {
            header(HttpHeaders.Authorization, authorization)
            contentType(ContentType.Application.Json)
            setBody(ByteReadChannel(body))
        }

    @Test
    fun `a raw body of exactly 131072 bytes is accepted`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val body = paddedPush(rawLimit)
        assertEquals("the body is exactly the limit", rawLimit, body.size)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, body)
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }
        assertEquals("one row stored", 1L, storedRowCount())
    }

    @Test
    fun `a raw body of 131073 bytes answers 413 and stores nothing`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val body = paddedPush(rawLimit + 1)
        assertEquals("the body is one over the limit", rawLimit + 1, body.size)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, body)
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("body", PAYLOAD_TOO_LARGE_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a body without a declared length that runs past the limit answers 413`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val body = paddedPush(rawLimit + 1)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postWithoutLength(auth, body)
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("body", PAYLOAD_TOO_LARGE_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a chunked body of exactly the limit is accepted`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val body = paddedPush(rawLimit)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postWithoutLength(auth, body)
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }
        assertEquals("one row stored", 1L, storedRowCount())
    }

    @Test
    fun `an encrypted text decoding to exactly 65536 bytes is accepted`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val encrypted = encryptedOf(syncBodyOfSize(decodedLimit))
        assertEquals("the decoded body is exactly the limit", decodedLimit, utf8(syncBodyOfSize(decodedLimit)).size)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", encrypted)))
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }
        assertEquals("one row stored", 1L, storedRowCount())
    }

    @Test
    fun `an encrypted text decoding to 65537 bytes answers 413 and stores nothing`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val encrypted = encryptedOf(syncBodyOfSize(decodedLimit + 1))
        assertEquals("the decoded body is one over the limit", decodedLimit + 1, utf8(syncBodyOfSize(decodedLimit + 1)).size)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", encrypted)))
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("body", PAYLOAD_TOO_LARGE_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `an unauthenticated oversize request answers 401, not 413`() = runBlocking<Unit> {
        account("alice", 1)
        val body = paddedPush(rawLimit + 1)
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(null, body)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_SESSION_BODY, response.bodyAsText())
        }
        assertTrue("nothing stored", storedRowCount() == 0L)
    }
}
