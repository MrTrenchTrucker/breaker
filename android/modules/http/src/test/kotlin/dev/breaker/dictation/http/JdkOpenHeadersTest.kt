package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HEADERS_OPEN_URL = "https://example.test/open-headers-path"

/** The request this file sends. The other files' request helpers are private to those files. */
private fun headersOpenRequest(cap: Long): CallRequest =
    CallRequest(CallMethod.GET, HEADERS_OPEN_URL, emptyList(), null, limitsOf(cap))

/** Open-path header tests: the real transport over a scripted FakeHttpConnection. */
class JdkOpenHeadersTest {

    @Test
    fun `an open reply carries every reply header as one pair per value and leaves out the status line`() {
        val connection = FakeHttpConnection(200, 2L, CountingBody("ok".toByteArray()))
        connection.replyHeaders = linkedMapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 200 OK"),
            "Retry-After" to listOf("30"),
            "X-Twice" to listOf("a", "b"),
        )
        val reply = transportOver(connection, SeamRecord()).open(headersOpenRequest(1_000L))
        assertEquals(
            listOf("Retry-After" to "30", "X-Twice" to "a", "X-Twice" to "b"),
            reply.headers,
        )
        reply.close()
    }

    @Test
    fun `a header the server did not send is absent from an open reply`() {
        val connection = FakeHttpConnection(200, 2L, CountingBody("ok".toByteArray()))
        connection.replyHeaders = linkedMapOf<String?, List<String>>(
            "X-Sent" to listOf("1"),
            "X-Twice" to listOf("a", "b"),
        )
        val reply = transportOver(connection, SeamRecord()).open(headersOpenRequest(1_000L))
        assertEquals(
            listOf("X-Sent" to "1", "X-Twice" to "a", "X-Twice" to "b"),
            reply.headers,
        )
        assertTrue("a header the server did not send was invented", !reply.headers.any { it.first == "X-Absent" })
        reply.close()
    }

    @Test
    fun `an open reply of a connection with no headers has an empty header list`() {
        val connection = FakeHttpConnection(200, 2L, CountingBody("ok".toByteArray()))
        val reply = transportOver(connection, SeamRecord()).open(headersOpenRequest(1_000L))
        assertTrue("headers were invented", reply.headers.isEmpty())
        reply.close()
    }
}
