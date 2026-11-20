package dev.breaker.dictation.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SEND_URL = "https://example.test/put-send-path"

private fun putRequest(cap: Long): CallRequest =
    CallRequest(CallMethod.PUT, SEND_URL, emptyList(), null, limitsOf(cap))

class JdkSendPutTest {

    @Test
    fun `a send PUT body goes out with a fixed length`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        val request = CallRequest(CallMethod.PUT, SEND_URL, emptyList(), payload, limitsOf(1_000L))
        transportOver(connection, SeamRecord()).send(request)
        assertEquals("PUT", connection.requestMethod)
        assertEquals(payload.size, connection.fixedLength)
        assertArrayEquals(payload, connection.sentBody.toByteArray())
        assertEquals(1, connection.connects)
        assertEquals(true, connection.doOutput)
    }

    @Test
    fun `a send PUT with no body sends no body and no fixed length`() {
        val connection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))
        transportOver(connection, SeamRecord()).send(putRequest(1_000L))
        assertEquals(-1, connection.fixedLength)
        assertEquals(0, connection.sentBody.size())
        assertEquals(false, connection.doOutput)
    }

    @Test
    fun `an open PUT returns the stream reply under the cap`() {
        val connection = FakeHttpConnection(200, 10L, CountingBody(ByteArray(10) { 1 }))
        val reply = transportOver(connection, SeamRecord()).open(putRequest(10L))
        assertEquals(10, readAllOf(reply.body).size)
        reply.close()
    }
}
