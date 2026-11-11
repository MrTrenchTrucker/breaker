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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests for [SyncHttpUploader.delete]. The upload half stays in [SyncHttpUploaderTest] to keep each
 * file under the 300 line gate cap.
 */
class SyncHttpUploaderDeleteTest {

    companion object {
        private const val TOKEN_A = "token-a"
        private const val BASE = "https://server.test"
        private const val CLIENT_ID = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
        private const val INVALID_CLIENT_ID = "not-a-client-id"
    }

    private val limits = CallLimits(10_000, 30_000, 1_048_576L)

    private fun session(base: String = BASE, token: String? = TOKEN_A): AddressedSession =
        AddressedSession(base, AuthSession("u1", "user", token ?: "", UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

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
    fun `delete sends DELETE to base plus v1 transcriptions slash clientId`() {
        val transport = FakeTransport()
        val up = uploader({ session() }, transport)
        assertEquals("204 maps to CONFIRMED", UploadOutcome.CONFIRMED, up.delete(CLIENT_ID))
        val req = transport.requests.single()
        assertEquals("delete method is DELETE", CallMethod.DELETE, req.method)
        assertEquals("delete url is base + /v1/transcriptions/{clientId}", BASE + "/v1/transcriptions/$CLIENT_ID", req.url)
        val headerNames = req.headers.map { it.first }
        assertEquals("delete sends exactly the Authorization header", listOf("Authorization"), headerNames)
        assertTrue("delete sends no body", req.body == null)
    }

    @Test
    fun `delete 401 maps to FAILED`() {
        val transport = FakeTransport().apply { reply = BytesReply(401, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("401 maps to FAILED", UploadOutcome.FAILED, up.delete(CLIENT_ID))
    }

    @Test
    fun `delete 403 maps to FAILED`() {
        val transport = FakeTransport().apply { reply = BytesReply(403, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("403 maps to FAILED", UploadOutcome.FAILED, up.delete(CLIENT_ID))
    }

    @Test
    fun `delete 409 maps to FAILED`() {
        val transport = FakeTransport().apply { reply = BytesReply(409, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("409 maps to FAILED", UploadOutcome.FAILED, up.delete(CLIENT_ID))
    }

    @Test
    fun `delete 500 maps to FAILED`() {
        val transport = FakeTransport().apply { reply = BytesReply(500, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        assertEquals("500 maps to FAILED", UploadOutcome.FAILED, up.delete(CLIENT_ID))
    }

    @Test
    fun `delete 400 throws PermanentRefusalException`() {
        val transport = FakeTransport().apply { reply = BytesReply(400, ByteArray(0)) }
        val up = uploader({ session() }, transport)
        val thrown = try {
            up.delete(CLIENT_ID)
            null
        } catch (e: PermanentRefusalException) { e }
        val refusal = thrown ?: throw AssertionError("a 400 was not a PermanentRefusalException")
        assertEquals("status is 400", 400, refusal.status)
    }

    @Test
    fun `delete IOException maps to UNREACHABLE`() {
        val transport = FakeTransport().apply { sendThrows = IOException("connection refused") }
        val up = uploader({ session() }, transport)
        assertEquals("network failure maps to UNREACHABLE", UploadOutcome.UNREACHABLE, up.delete(CLIENT_ID))
    }

    @Test
    fun `delete with an invalid client id returns FAILED and sends nothing`() {
        val transport = FakeTransport()
        val up = uploader({ session() }, transport)
        assertEquals("invalid id maps to FAILED", UploadOutcome.FAILED, up.delete(INVALID_CLIENT_ID))
        assertTrue("no request was sent for an invalid id", transport.requests.isEmpty())
    }

    @Test
    fun `delete with no session returns FAILED and sends nothing`() {
        val transport = FakeTransport()
        val up = uploader({ null }, transport)
        assertEquals("no session maps to FAILED", UploadOutcome.FAILED, up.delete(CLIENT_ID))
        assertTrue("no request was sent", transport.requests.isEmpty())
    }
}
