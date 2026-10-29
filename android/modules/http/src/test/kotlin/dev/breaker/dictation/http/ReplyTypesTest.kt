package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** A body that records whether it was closed. */
private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}

class ReplyTypesTest {

    private val bodyText = "canary-reply-body"
    private val location = "https://example.test/canary-location"

    @Test
    fun `the bytes reply keeps its status and its body`() {
        val reply = BytesReply(200, bodyText.toByteArray())
        assertEquals(200, reply.status)
        assertEquals(bodyText, reply.body.decodeToString())
    }

    @Test
    fun `the bytes reply string prints the status only`() {
        val reply = BytesReply(200, bodyText.toByteArray())
        assertEquals("BytesReply(status=200)", reply.toString())
        assertTrue("the reply string holds no body text", !reply.toString().contains(bodyText))
    }

    @Test
    fun `the stream reply keeps its status location type and length`() {
        val reply = StreamReply(302, location, "text/plain", -1L, ByteArrayInputStream(ByteArray(0)))
        assertEquals(302, reply.status)
        assertEquals(location, reply.location)
        assertEquals("text/plain", reply.contentType)
        assertEquals(-1L, reply.contentLength)
    }

    @Test
    fun `the stream reply string prints the status only`() {
        val reply = StreamReply(302, location, null, 0L, ByteArrayInputStream(ByteArray(0)))
        assertEquals("StreamReply(status=302)", reply.toString())
        assertTrue("the reply string holds no location", !reply.toString().contains("canary-location"))
    }

    @Test
    fun `closing the stream reply closes its body`() {
        val body = TrackingStream(bodyText.toByteArray())
        val reply = StreamReply(200, null, null, -1L, body)
        assertTrue("the body was closed before the reply was", !body.closed)
        reply.close()
        assertTrue("closing the reply did not close the body", body.closed)
    }
}
