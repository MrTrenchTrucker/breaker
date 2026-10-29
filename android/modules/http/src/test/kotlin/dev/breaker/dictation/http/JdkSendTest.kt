package dev.breaker.dictation.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val SEND_URL = "https://example.test/send-path"

private fun getRequest(cap: Long): CallRequest =
    CallRequest(CallMethod.GET, SEND_URL, emptyList(), null, limitsOf(cap))

class JdkSendTest {

    @Test
    fun `a send reply comes back with its status and its body`() {
        val connection = FakeHttpConnection(200, 2L, CountingBody("ok".toByteArray()))
        val reply = transportOver(connection, SeamRecord()).send(getRequest(1_000L))
        assertEquals(200, reply.status)
        assertArrayEquals("ok".toByteArray(), reply.body)
    }

    @Test
    fun `a send refuses an announced length over the cap before any body byte is read`() {
        val body = CountingBody(ByteArray(2_000))
        val connection = FakeHttpConnection(200, 2_000L, body)
        val refusal = refusedWith { transportOver(connection, SeamRecord()).send(getRequest(1_000L)) }
        assertTrue("no refusal for an announced length over the cap", refusal != null)
        assertEquals(HttpRefusalReason.REPLY_TOO_LARGE, refusal?.reason)
        assertEquals("a body byte was read", 0, body.reads)
    }

    @Test
    fun `a send refuses a body that reaches the cap plus one byte when no length is announced`() {
        val connection = FakeHttpConnection(200, -1L, CountingBody(ByteArray(1_001)))
        val refusal = refusedWith { transportOver(connection, SeamRecord()).send(getRequest(1_000L)) }
        assertEquals(HttpRefusalReason.REPLY_TOO_LARGE, refusal?.reason)
    }

    @Test
    fun `a send accepts a body of exactly the cap`() {
        val connection = FakeHttpConnection(200, -1L, CountingBody(ByteArray(1_000)))
        val reply = transportOver(connection, SeamRecord()).send(getRequest(1_000L))
        assertEquals(1_000, reply.body.size)
    }

    @Test
    fun `a send stops reading at the cap and does not read an endless body`() {
        val endless = EndlessBody()
        val connection = FakeHttpConnection(200, -1L, endless)
        val refusal = refusedWith { transportOver(connection, SeamRecord()).send(getRequest(1_000L)) }
        assertEquals(HttpRefusalReason.REPLY_TOO_LARGE, refusal?.reason)
        assertTrue("the body was read past the cap plus one byte: ${endless.served}", endless.served <= 1_001L)
    }

    @Test
    fun `a send body shorter than its announced length is a length mismatch`() {
        val connection = FakeHttpConnection(200, 1_000L, CountingBody(ByteArray(100)))
        val refusal = refusedWith { transportOver(connection, SeamRecord()).send(getRequest(1_000L)) }
        assertEquals(HttpRefusalReason.LENGTH_MISMATCH, refusal?.reason)
    }

    @Test
    fun `a send non 2xx reply reads its error body under the cap`() {
        val success = CountingBody("never".toByteArray())
        val connection = FakeHttpConnection(503, 4L, success)
        connection.errorBody = CountingBody("busy".toByteArray())
        val reply = transportOver(connection, SeamRecord()).send(getRequest(1_000L))
        assertEquals(503, reply.status)
        assertArrayEquals("busy".toByteArray(), reply.body)
        assertEquals("the success stream was read for a non 2xx reply", 0, success.reads)
    }

    @Test
    fun `a send non 2xx body over the cap is refused`() {
        val connection = FakeHttpConnection(503, -1L, CountingBody(ByteArray(0)))
        connection.errorBody = CountingBody(ByteArray(1_001))
        val refusal = refusedWith { transportOver(connection, SeamRecord()).send(getRequest(1_000L)) }
        assertEquals(HttpRefusalReason.REPLY_TOO_LARGE, refusal?.reason)
    }

    @Test
    fun `a send non 2xx reply without an error stream has an empty body`() {
        val connection = FakeHttpConnection(404, 0L, CountingBody(ByteArray(0)))
        val reply = transportOver(connection, SeamRecord()).send(getRequest(1_000L))
        assertEquals(404, reply.status)
        assertEquals(0, reply.body.size)
    }

    @Test
    fun `a send 3xx reply comes back with its body`() {
        val connection = FakeHttpConnection(302, 5L, CountingBody("moved".toByteArray()))
        connection.location = "https://example.test/elsewhere"
        val reply = transportOver(connection, SeamRecord()).send(getRequest(1_000L))
        assertEquals(302, reply.status)
        assertArrayEquals("moved".toByteArray(), reply.body)
    }

    @Test
    fun `a send 3xx reply is returned as it came and not followed`() {
        val connection = FakeHttpConnection(302, 0L, CountingBody(ByteArray(0)))
        connection.location = "https://example.test/elsewhere"
        val record = SeamRecord()
        val reply = transportOver(connection, record).send(getRequest(1_000L))
        assertEquals(302, reply.status)
        assertEquals("the redirect was followed", false, connection.instanceFollowRedirects)
        assertEquals("the address was opened more than once", 1, record.opened)
    }

    @Test
    fun `a send sets the timeouts the method and no redirect or cache from the request`() {
        val limits = CallLimits(connectTimeoutMs = 1_234, readTimeoutMs = 4_321, maxReplyBytes = 1_000L)
        val request = CallRequest(CallMethod.POST, SEND_URL, emptyList(), "x".toByteArray(), limits)
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        transportOver(connection, SeamRecord()).send(request)
        assertEquals(1_234, connection.connectTimeout)
        assertEquals(4_321, connection.readTimeout)
        assertEquals("POST", connection.requestMethod)
        assertEquals(false, connection.instanceFollowRedirects)
        assertEquals(false, connection.useCaches)
        assertEquals(true, connection.doOutput)
        assertEquals(true, connection.doInput)
    }

    @Test
    fun `a send POST body goes out with a fixed length`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        val request = CallRequest(CallMethod.POST, SEND_URL, emptyList(), payload, limitsOf(1_000L))
        transportOver(connection, SeamRecord()).send(request)
        assertEquals(5, connection.fixedLength)
        assertArrayEquals(payload, connection.sentBody.toByteArray())
        assertEquals(1, connection.connects)
    }

    @Test
    fun `a send GET sends no body and no fixed length`() {
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        transportOver(connection, SeamRecord()).send(getRequest(1_000L))
        assertEquals(-1, connection.fixedLength)
        assertEquals(0, connection.sentBody.size())
        assertEquals(false, connection.doOutput)
    }

    @Test
    fun `a send request headers are set on the connection`() {
        val headers = listOf("Accept" to "application/json", "Authorization" to "Bearer canary-key")
        val request = CallRequest(CallMethod.GET, SEND_URL, headers, null, limitsOf(1_000L))
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        transportOver(connection, SeamRecord()).send(request)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("Bearer canary-key", connection.getRequestProperty("Authorization"))
    }

    @Test
    fun `a send connection problem is an IOException and releases the connection`() {
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        connection.connectFails = true
        val failure = failureOf { transportOver(connection, SeamRecord()).send(getRequest(1_000L)) }
        assertTrue("a connection problem was expected", failure != null && failure !is HttpRefusal)
        assertEquals("the connection was not released", 1, connection.disconnects)
    }

    @Test
    fun `a send status below 100 is an IOException with a fixed message and no body read`() {
        val body = CountingBody("never".toByteArray())
        val connection = FakeHttpConnection(-1, 5L, body)
        val failure = failureOf { transportOver(connection, SeamRecord()).send(getRequest(1_000L)) }
        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals("the server sent no usable status line", failure?.message)
        assertEquals("a body byte was read", 0, body.reads)
        assertEquals("the connection was not released", 1, connection.disconnects)
    }

    @Test
    fun `a send cap above MAX_BYTES_REPLY is refused before any connection`() {
        val record = SeamRecord()
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        val refused = argumentRefusalOf {
            transportOver(connection, record).send(getRequest(CallLimits.MAX_BYTES_REPLY + 1L))
        }
        assertTrue("a cap above the send ceiling was accepted", refused != null)
        assertEquals("a connection was opened", 0, record.opened)
    }

    @Test
    fun `a send cap of exactly MAX_BYTES_REPLY is accepted`() {
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        val reply = transportOver(connection, SeamRecord()).send(getRequest(CallLimits.MAX_BYTES_REPLY))
        assertEquals(200, reply.status)
    }
}
