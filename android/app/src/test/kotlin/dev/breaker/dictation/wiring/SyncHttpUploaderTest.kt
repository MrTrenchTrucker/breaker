package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.sync.PermanentRefusalException
import dev.breaker.dictation.sync.SyncUploader
import dev.breaker.dictation.sync.UploadOutcome
import dev.breaker.shared.api.SyncPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncHttpUploaderTest {

    companion object {
        private const val TOKEN_A = "token-a"
        private const val TOKEN_B = "token-b"
        private const val BASE = "https://server.test"
    }

    private val limits = CallLimits(10_000, 30_000, 1_048_576L)

    private fun session(base: String = BASE, token: String? = TOKEN_A): AddressedSession =
        AddressedSession(base, AuthSession("u1", "user", token ?: "", UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

    private fun push(resave: Boolean? = null): SyncPush =
        SyncPush("0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f", 0, "eyJ2IjoxfQ==", resave)

    /** A fake transport: records every request and answers with a canned reply. No network. */
    private class FakeTransport : HttpTransport {
        var requests: MutableList<CallRequest> = mutableListOf()
        var reply: BytesReply = BytesReply(204, ByteArray(0))
        var sendThrows: IOException? = null

        override fun send(request: CallRequest): BytesReply {
            requests.add(request)
            val failure = sendThrows
            if (failure != null) throw failure
            return reply
        }

        override fun open(request: CallRequest): dev.breaker.dictation.http.StreamReply =
            throw UnsupportedOperationException("open is not used by the uploader")
    }

    private fun uploader(session: () -> AddressedSession?, transport: FakeTransport): SyncHttpUploader =
        SyncHttpUploader(transport, limits, session)

    @Test
    fun `204 answers CONFIRMED`() {
        val transport = FakeTransport()
        val up = uploader({ session() }, transport)
        assertEquals("204 maps to CONFIRMED", UploadOutcome.CONFIRMED, up.upload(push()))
        assertEquals("exactly one request sent", 1, transport.requests.size)
    }

    @Test
    fun `401 answers FAILED and sends nothing else`() {
        val transport = FakeTransport().apply { reply = BytesReply(401, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("401 maps to FAILED", UploadOutcome.FAILED, up.upload(push()))
        assertEquals("one request only", 1, transport.requests.size)
    }

    @Test
    fun `403 answers FAILED`() {
        val transport = FakeTransport().apply { reply = BytesReply(403, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("403 maps to FAILED", UploadOutcome.FAILED, up.upload(push()))
    }

    @Test
    fun `409 answers FAILED and does not throw`() {
        val transport = FakeTransport().apply { reply = BytesReply(409, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("409 maps to FAILED", UploadOutcome.FAILED, up.upload(push()))
    }

    @Test
    fun `400 throws PermanentRefusalException carrying 400 and bad_request`() {
        val transport = FakeTransport().apply { reply = BytesReply(400, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        val thrown = try {
            up.upload(push())
            null
        } catch (e: PermanentRefusalException) { e }
        val refusal = thrown ?: throw AssertionError("a 400 was not a PermanentRefusalException")
        assertEquals("status is 400", 400, refusal.status)
        assertEquals("reason is the server's error word", "bad_request", refusal.reason)
    }

    @Test
    fun `413 throws PermanentRefusalException carrying 413`() {
        val transport = FakeTransport().apply { reply = BytesReply(413, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        val thrown = try {
            up.upload(push())
            null
        } catch (e: PermanentRefusalException) { e }
        val refusal = thrown ?: throw AssertionError("a 413 was not a PermanentRefusalException")
        assertEquals("status is 413", 413, refusal.status)
        assertEquals("reason is payload_too_large", "payload_too_large", refusal.reason)
    }

    @Test
    fun `IOException from send answers UNREACHABLE`() {
        val transport = FakeTransport().apply { sendThrows = IOException("connection refused") }
        val up = uploader({ session() }, transport)
        assertEquals("network failure maps to UNREACHABLE", UploadOutcome.UNREACHABLE, up.upload(push()))
    }

    @Test
    fun `500 answers FAILED`() {
        val transport = FakeTransport().apply { reply = BytesReply(500, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("any other status maps to FAILED", UploadOutcome.FAILED, up.upload(push()))
    }

    @Test
    fun `request carries Content-Type application json`() {
        val transport = FakeTransport()
        val up = uploader({ session() }, transport)
        up.upload(push())
        val header = transport.requests.single().headers
            .firstOrNull { it.first == "Content-Type" }?.second
        assertEquals("Content-Type header is application/json", "application/json", header)
    }

    @Test
    fun `request carries Authorization Bearer token read per call`() {
        val transport = FakeTransport()
        val up = uploader({ session(token = TOKEN_A) }, transport)
        up.upload(push())
        val first = transport.requests.single()
            .headers.first { it.first == "Authorization" }.second
        assertEquals("Authorization header bears the token", "Bearer " + TOKEN_A, first)

        // A second call with a different token shows the second token on the second request:
        // the supplier is re-read per call and nothing is cached.
        transport.requests.clear()
        val upB = uploader({ session(token = TOKEN_B) }, transport)
        upB.upload(push())
        val second = transport.requests.single()
            .headers.first { it.first == "Authorization" }.second
        assertEquals("second call uses the second token", "Bearer " + TOKEN_B, second)
    }

    @Test
    fun `no session no request is built and nothing is sent`() {
        val transport = FakeTransport()
        val up = uploader({ null }, transport)
        val result = up.upload(push())
        assertEquals("no session maps to FAILED", UploadOutcome.FAILED, result)
        assertTrue("no request was sent", transport.requests.isEmpty())
    }

    @Test
    fun `no address no request is built and nothing is sent`() {
        val transport = FakeTransport()
        val up = uploader({ session(base = "") }, transport)
        assertEquals("blank address maps to FAILED", UploadOutcome.FAILED, up.upload(push()))
        assertTrue("no request was sent", transport.requests.isEmpty())
    }

    @Test
    fun `token never appears in the thrown exception's message or toString`() {
        val transport = FakeTransport().apply { reply = BytesReply(400, ByteArray(0)) }
        val up = uploader({ session(token = TOKEN_A) }, transport)
        val thrown = try {
            up.upload(push())
            null
        } catch (e: PermanentRefusalException) { e }
        val refusal = thrown ?: throw AssertionError("a 400 was not a PermanentRefusalException")
        assertFalse("message must not hold the token", refusal.message.orEmpty().contains(TOKEN_A))
        assertFalse("toString must not hold the token", refusal.toString().contains(TOKEN_A))
    }

    @Test
    fun `envelope for null resave is a 3-key object with no resave`() {
        val transport = FakeTransport()
        val up = uploader({ session() }, transport)
        up.upload(push(resave = null))
        val body = String(transport.requests.single().body ?: ByteArray(0), charset = java.nio.charset.StandardCharsets.UTF_8)
        assertTrue("3-key object: contains client_id", body.contains("\"client_id\""))
        assertTrue("3-key object: contains key_version", body.contains("\"key_version\""))
        assertTrue("3-key object: contains encrypted", body.contains("\"encrypted\""))
        assertFalse("3-key object: contains no resave key", body.contains("\"resave\""))
    }

    @Test
    fun `non-null resave appears as a bare true false literal`() {
        val transport = FakeTransport()
        val upTrue = uploader({ session() }, transport)
        upTrue.upload(push(resave = true))
        val bodyTrue = String(transport.requests.single().body ?: ByteArray(0), charset = java.nio.charset.StandardCharsets.UTF_8)
        assertTrue("resave true is a bare literal", bodyTrue.contains("\"resave\":true"))

        transport.requests.clear()
        val upFalse = uploader({ session() }, transport)
        upFalse.upload(push(resave = false))
        val bodyFalse = String(transport.requests.single().body ?: ByteArray(0), charset = java.nio.charset.StandardCharsets.UTF_8)
        assertTrue("resave false is a bare literal", bodyFalse.contains("\"resave\":false"))
    }

    @Test
    fun `token never appears in any request header other than Authorization`() {
        val transport = FakeTransport()
        val up = uploader({ session() }, transport)
        up.upload(push())
        val headers = transport.requests.single().headers.map { it.first }
        assertTrue("only Content-Type and Authorization are sent", headers.contains("Content-Type"))
        assertTrue("only Content-Type and Authorization are sent", headers.contains("Authorization"))
        assertEquals("exactly two headers", 2, headers.size)
    }

    @Test
    fun `request method is POST and url is base plus v1 sync`() {
        val transport = FakeTransport()
        val up = uploader({ session() }, transport)
        up.upload(push())
        val req = transport.requests.single()
        assertEquals("method is POST", CallMethod.POST, req.method)
        assertEquals("url is base + /v1/sync", BASE + "/v1/sync", req.url)
    }
}
