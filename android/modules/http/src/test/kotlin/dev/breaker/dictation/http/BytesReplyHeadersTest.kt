package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Test

/** A reply built without headers has none; one built with headers keeps them in order. */
class BytesReplyHeadersTest {
    @Test
    fun `a reply built without headers has an empty header list`() {
        val reply = BytesReply(200, ByteArray(0))
        assertEquals("android_http: no headers by default", emptyList<Pair<String, String>>(), reply.headers)
    }

    @Test
    fun `a reply built with headers keeps them in order`() {
        val headers = listOf("Retry-After" to "30", "X-Other" to "1")
        val reply = BytesReply(429, ByteArray(0), headers)
        assertEquals("android_http: the given headers", headers, reply.headers)
    }
}
