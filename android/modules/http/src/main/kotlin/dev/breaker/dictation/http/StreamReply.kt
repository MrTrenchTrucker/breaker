package dev.breaker.dictation.http

import java.io.Closeable
import java.io.InputStream

/**
 * The reply of the open call. The caller reads [body] and then closes the reply.
 *
 * [contentLength] is -1 when the length is unknown. Closing the reply closes the body, and the
 * transport ties the body to its connection, so closing the reply drops that connection too.
 *
 * [headers] holds every reply header of an open as one (name, value) pair per value, the status line
 * left out; a reply built with the five-argument constructor has no headers.
 */
class StreamReply(
    val status: Int,
    val location: String?,
    val contentType: String?,
    val contentLength: Long,
    val body: InputStream,
    val headers: List<Pair<String, String>>,
) : Closeable {
    /** A reply that carries no headers. */
    constructor(
        status: Int,
        location: String?,
        contentType: String?,
        contentLength: Long,
        body: InputStream,
    ) : this(status, location, contentType, contentLength, body, emptyList())

    override fun close() {
        body.close()
    }

    override fun toString(): String = "StreamReply(status=$status)"
}
