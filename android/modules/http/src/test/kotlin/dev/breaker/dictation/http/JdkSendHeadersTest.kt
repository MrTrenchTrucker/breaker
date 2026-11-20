package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Test

private const val HEADERS_SEND_URL = "https://example.test/send-headers-path"

/** The request this file sends. The other files' request helpers are private to those files. */
private fun headersSendRequest(cap: Long): CallRequest =
    CallRequest(CallMethod.GET, HEADERS_SEND_URL, emptyList(), null, limitsOf(cap))

/** Send-path header guard: BytesReply headers are unchanged. */
class JdkSendHeadersTest {

    @Test
    fun `a send reply carries the headers replyHeaders collects`() {
        val connection = FakeHttpConnection(200, 2L, CountingBody("ok".toByteArray()))
        connection.replyHeaders = linkedMapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 200 OK"),
            "Retry-After" to listOf("30"),
            "X-Twice" to listOf("a", "b"),
        )
        val reply = transportOver(connection, SeamRecord()).send(headersSendRequest(1_000L))
        assertEquals(
            listOf("Retry-After" to "30", "X-Twice" to "a", "X-Twice" to "b"),
            reply.headers,
        )
    }
}
