package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Type-level StreamReply header tests: built directly, not over a transport. */
class ReplyTypesStreamHeadersTest {

    @Test
    fun `a stream reply built with five arguments has an empty header list`() {
        val reply = StreamReply(200, null, null, -1L, ByteArrayInputStream(ByteArray(0)))
        assertEquals("a five-argument reply was given headers", emptyList<Pair<String, String>>(), reply.headers)
        reply.close()
    }

    @Test
    fun `the stream reply string prints the status only with a header canary absent from it`() {
        val reply = StreamReply(302, "https://example.test/next", null, 5L, ByteArrayInputStream("moved".toByteArray()), listOf("X-Canary" to "never-in-toString"))
        assertEquals("StreamReply(status=302)", reply.toString())
        assertTrue("a header canary entered the string", !reply.toString().contains("never-in-toString"))
        reply.close()
    }
}
