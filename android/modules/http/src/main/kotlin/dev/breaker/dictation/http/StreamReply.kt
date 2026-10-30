package dev.breaker.dictation.http

import java.io.Closeable
import java.io.InputStream

/**
 * The reply of the open call. The caller reads [body] and then closes the reply.
 *
 * [contentLength] is -1 when the length is unknown. Closing the reply closes the body, and the
 * transport ties the body to its connection, so closing the reply drops that connection too.
 */
class StreamReply(
    val status: Int,
    val location: String?,
    val contentType: String?,
    val contentLength: Long,
    val body: InputStream,
) : Closeable {
    override fun close() {
        body.close()
    }

    override fun toString(): String = "StreamReply(status=$status)"
}
