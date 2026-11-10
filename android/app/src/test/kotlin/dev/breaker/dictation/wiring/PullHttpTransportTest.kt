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
import dev.breaker.dictation.sync.PullTransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The pull adapter over a fake transport. A PullRequest can only be built inside the sync module,
 * so these tests drive fetchPath with the path and the query text that PullRequest.path() and
 * PullRequest.query() give (the encoding itself is tested in the sync module, PullRequestTest).
 */
class PullHttpTransportTest {

    companion object {
        private const val TOKEN_A = "token-a"
        private const val TOKEN_B = "token-b"
        private const val BASE = "https://server.test"
        private const val PATH = "/v1/transcriptions"
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
            throw UnsupportedOperationException("open is not used by the pull adapter")
    }

    private fun adapter(transport: FakeTransport, supplier: () -> AddressedSession? = { session() }): PullHttpTransport =
        PullHttpTransport(transport, limits, supplier)

    private fun answered(result: PullTransportResult): PullTransportResult.Answered {
        assertTrue("android_app: expected Answered, got a different result", result is PullTransportResult.Answered)
        return result as PullTransportResult.Answered
    }

    private fun answerFor(status: Int, body: String): PullTransportResult.Answered {
        val transport = FakeTransport().apply { reply = BytesReply(status, body.toByteArray(Charsets.UTF_8)) }
        return answered(adapter(transport).fetchPath(PATH, ""))
    }

    @Test
    fun `200 passes the status and the page text through`() {
        val result = answerFor(200, "{\"items\":[],\"next\":\"1-0\",\"more\":false}")
        assertEquals("android_app: status", 200, result.status)
        assertEquals("android_app: body", "{\"items\":[],\"next\":\"1-0\",\"more\":false}", result.body)
    }

    @Test
    fun `every status the pull loop reads is passed through unchanged`() {
        for (status in listOf(400, 401, 403, 404, 410, 500)) {
            val result = answerFor(status, "text of $status")
            assertEquals("android_app: status $status", status, result.status)
            assertEquals("android_app: body of $status", "text of $status", result.body)
        }
    }

    @Test
    fun `a body that holds the token text is passed through and the result string form holds nothing`() {
        val result = answerFor(401, "the server says $TOKEN_A here")
        assertEquals("android_app: body is exactly as received", "the server says token-a here", result.body)
        assertFalse("android_app: the string form of the result must not hold the token", result.toString().contains(TOKEN_A))
        assertFalse("android_app: the string form of the result must not hold the body", result.toString().contains("server says"))
    }

    @Test
    fun `an empty 200 body answers Answered with an empty text`() {
        val result = answerFor(200, "")
        assertEquals("android_app: status", 200, result.status)
        assertEquals("android_app: body", "", result.body)
    }

    @Test
    fun `valid multibyte UTF-8 is decoded`() {
        val transport = FakeTransport().apply { reply = BytesReply(200, byteArrayOf(0xC3.toByte(), 0xA9.toByte())) }
        val result = answered(adapter(transport).fetchPath(PATH, ""))
        assertEquals("android_app: the two bytes C3 A9 are one e with acute accent", "é", result.body)
    }

    @Test
    fun `a body that is not valid UTF-8 answers Unreachable`() {
        val transport = FakeTransport().apply { reply = BytesReply(200, byteArrayOf(0xFF.toByte(), 0xFE.toByte())) }
        assertSame("android_app: bad bytes must not turn into text", PullTransportResult.Unreachable, adapter(transport).fetchPath(PATH, ""))
    }

    @Test
    fun `an IOException from the transport answers Unreachable`() {
        val transport = FakeTransport().apply { sendThrows = IOException("connection refused") }
        assertSame("android_app: a connection problem", PullTransportResult.Unreachable, adapter(transport).fetchPath(PATH, ""))
    }

    @Test
    fun `every refusal reason of the http module answers Unreachable`() {
        for (reason in HttpRefusalReason.values()) {
            val transport = FakeTransport().apply { sendThrows = HttpRefusal(reason) }
            assertSame("android_app: refusal $reason", PullTransportResult.Unreachable, adapter(transport).fetchPath(PATH, ""))
            assertEquals("android_app: one request was tried for $reason", 1, transport.requests.size)
        }
    }

    @Test
    fun `the first request has no query in the url`() {
        val transport = FakeTransport()
        adapter(transport).fetchPath(PATH, "")
        assertEquals("android_app: url", "https://server.test/v1/transcriptions", transport.requests.single().url)
    }

    @Test
    fun `a cursor query is appended exactly as given`() {
        val transport = FakeTransport()
        adapter(transport).fetchPath(PATH, "since=7-42%20a%2Fb")
        assertEquals("android_app: url", "https://server.test/v1/transcriptions?since=7-42%20a%2Fb", transport.requests.single().url)
    }

    @Test
    fun `the request is a GET with no body and the limits of the adapter`() {
        val transport = FakeTransport()
        adapter(transport).fetchPath(PATH, "")
        val request = transport.requests.single()
        assertEquals("android_app: method", CallMethod.GET, request.method)
        assertNull("android_app: a GET has no body", request.body)
        assertSame("android_app: the limits handed to the adapter", limits, request.limits)
    }

    @Test
    fun `the bearer header is the only header and is read once per call`() {
        val transport = FakeTransport()
        var token = TOKEN_A
        val subject = adapter(transport) { session(token = token) }
        subject.fetchPath(PATH, "")
        token = TOKEN_B
        subject.fetchPath(PATH, "")
        assertEquals(
            "android_app: first call and second call carry their own token",
            listOf(listOf("Authorization" to "Bearer token-a"), listOf("Authorization" to "Bearer token-b")),
            transport.requests.map { it.headers },
        )
    }

    @Test
    fun `no session means no request and Unreachable`() {
        val transport = FakeTransport()
        assertSame("android_app: no session", PullTransportResult.Unreachable, adapter(transport) { null }.fetchPath(PATH, ""))
        assertTrue("android_app: nothing was sent", transport.requests.isEmpty())
    }

    @Test
    fun `a blank address means no request and Unreachable`() {
        val transport = FakeTransport()
        assertSame("android_app: blank address", PullTransportResult.Unreachable, adapter(transport) { session(base = " ") }.fetchPath(PATH, ""))
        assertTrue("android_app: nothing was sent", transport.requests.isEmpty())
    }

    @Test
    fun `the session supplier is asked once for each call`() {
        val transport = FakeTransport()
        var asked = 0
        val subject = adapter(transport) { asked += 1; session() }
        subject.fetchPath(PATH, "")
        subject.fetchPath(PATH, "since=1-1")
        assertEquals("android_app: two calls, two reads", 2, asked)
    }
}
