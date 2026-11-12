package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.sync.OlderPullTransport
import dev.breaker.dictation.sync.PullTransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The older-rows adapter over a fake transport. It drives fetchPath(path, query) and checks
 * the url is base + /v1/transcriptions/older?older_than=<value>, the bearer header comes from
 * the addressed session, statuses pass through, and Unreachable answers for no session, blank base,
 * HttpRefusal, IOException and bad UTF-8. No network.
 */
class OlderRowsTransportTest {

    companion object {
        private const val TOKEN_A = "token-a"
        private const val TOKEN_B = "token-b"
        private const val BASE = "https://server.test"
        private const val PATH = "/v1/transcriptions/older"
    }

    private val limits = CallLimits(10_000, 30_000, 5_242_880L)

    private fun session(base: String = BASE, token: String = TOKEN_A): AddressedSession =
        AddressedSession(base, AuthSession("u1", "user", token, UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

    /** A fake transport: records every request and answers with a canned reply or a failure. No network. */
    private class FakeTransport : HttpTransport {
        var requests: MutableList<CallRequest> = mutableListOf()
        var reply: BytesReply = BytesReply(200, ByteArray(0))
        var sendThrows: IOException? = null

        override fun send(request: CallRequest): BytesReply {
            requests.add(request)
            val failure = sendThrows
            if (failure != null) throw failure
            return reply
        }

        override fun open(request: CallRequest): dev.breaker.dictation.http.StreamReply =
            throw UnsupportedOperationException("open is not used by the older-rows adapter")
    }

    private fun adapter(transport: FakeTransport, supplier: () -> AddressedSession? = { session() }): OlderRowsTransport =
        OlderRowsTransport(transport, limits, supplier)

    private fun answered(result: PullTransportResult): PullTransportResult.Answered {
        assertTrue("android_app: expected Answered, got a different result", result is PullTransportResult.Answered)
        return result as PullTransportResult.Answered
    }

    private fun answerFor(status: Int, body: String): PullTransportResult.Answered {
        val transport = FakeTransport().apply { reply = BytesReply(status, body.toByteArray(Charsets.UTF_8)) }
        return answered(adapter(transport).fetchPath(PATH, ""))
    }

    @Test
    fun `a 200 body passes through exactly as received`() {
        val result = answerFor(200, "{\"items\":[],\"next\":\"1700000000000\",\"more\":false}")
        assertEquals("android_app: status", 200, result.status)
        assertEquals("android_app: body", "{\"items\":[],\"next\":\"1700000000000\",\"more\":false}", result.body)
    }

    @Test
    fun `401 403 429 503 pass as their status`() {
        for (status in listOf(401, 403, 429, 503)) {
            val result = answerFor(status, "text of $status")
            assertEquals("android_app: status $status", status, result.status)
            assertEquals("android_app: body of $status", "text of $status", result.body)
        }
    }

    @Test
    fun `no session means Unreachable`() {
        val transport = FakeTransport()
        assertSame("android_app: no session", PullTransportResult.Unreachable, adapter(transport) { null }.fetchPath(PATH, ""))
        assertTrue("android_app: nothing was sent", transport.requests.isEmpty())
    }

    @Test
    fun `a blank base means Unreachable`() {
        val transport = FakeTransport()
        assertSame("android_app: blank address", PullTransportResult.Unreachable, adapter(transport) { session(base = " ") }.fetchPath(PATH, ""))
        assertTrue("android_app: nothing was sent", transport.requests.isEmpty())
    }

    @Test
    fun `HttpRefusal answers Unreachable`() {
        for (reason in HttpRefusalReason.values()) {
            val transport = FakeTransport().apply { sendThrows = HttpRefusal(reason) }
            assertSame("android_app: refusal $reason", PullTransportResult.Unreachable, adapter(transport).fetchPath(PATH, ""))
            assertEquals("android_app: one request was tried for $reason", 1, transport.requests.size)
        }
    }

    @Test
    fun `IOException answers Unreachable`() {
        val transport = FakeTransport().apply { sendThrows = IOException("connection refused") }
        assertSame("android_app: a connection problem", PullTransportResult.Unreachable, adapter(transport).fetchPath(PATH, ""))
    }

    @Test
    fun `bad UTF-8 body answers Unreachable`() {
        val transport = FakeTransport().apply { reply = BytesReply(200, byteArrayOf(0xFF.toByte(), 0xFE.toByte())) }
        assertSame("android_app: bad bytes must not turn into text", PullTransportResult.Unreachable, adapter(transport).fetchPath(PATH, ""))
    }

    @Test
    fun `the bearer header is the only header and is read once per call`() {
        val transport = FakeTransport()
        var token = TOKEN_A
        val subject = adapter(transport) { session(token = token) }
        subject.fetchPath(PATH, "older_than=1700000000000")
        token = TOKEN_B
        subject.fetchPath(PATH, "older_than=1700000000001")
        assertEquals(
            "android_app: first call and second call carry their own token",
            listOf(listOf("Authorization" to "Bearer token-a"), listOf("Authorization" to "Bearer token-b")),
            transport.requests.map { it.headers },
        )
    }

    @Test
    fun `the url is base plus the older path and the older_than query`() {
        val transport = FakeTransport()
        adapter(transport).fetchPath(PATH, "older_than=1700000000000-42")
        val request = transport.requests.single()
        assertEquals("android_app: url", "https://server.test/v1/transcriptions/older?older_than=1700000000000-42", request.url)
        assertEquals("android_app: method", CallMethod.GET, request.method)
        assertNull("android_app: a GET has no body", request.body)
        assertSame("android_app: the limits handed to the adapter", limits, request.limits)
    }

    @Test
    fun `every status the older read reads passes through unchanged`() {
        for (status in listOf(200, 400, 401, 403, 404, 410, 429, 500, 503)) {
            val result = answerFor(status, "text of $status")
            assertEquals("android_app: status $status", status, result.status)
        }
    }

    @Test
    fun `a body with surrounding whitespace passes through untrimmed`() {
        val result = answerFor(200, "  {\"items\":[]}\n ")
        assertEquals("android_app: body", "  {\"items\":[]}\n ", result.body)
    }

    @Test
    fun `the session is read once per call so a session that changes inside one call changes nothing`() {
        val transport = FakeTransport()
        var reads = 0
        val subject = adapter(transport) {
            reads++
            if (reads == 1) session(token = TOKEN_A) else session(base = "https://other.test", token = TOKEN_B)
        }
        subject.fetchPath(PATH, "older_than=1700000000000")
        assertEquals("android_app: the supplier was read once", 1, reads)
        val request = transport.requests.single()
        assertEquals("android_app: url from the first read", "https://server.test/v1/transcriptions/older?older_than=1700000000000", request.url)
        assertEquals("android_app: token from the first read", listOf("Authorization" to "Bearer token-a"), request.headers)
    }
}
