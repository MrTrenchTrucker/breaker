package dev.breaker.dictation.stt.ondevice

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens one HTTP GET and hands back the reply.
 *
 * This is the seam that keeps the platform's network stack out of the
 * fetcher's logic: everything above it can be exercised with a scripted
 * implementation, and [JdkHttpOpener] is the only code in this module that
 * opens a connection.
 */
internal fun interface HttpOpener {
    /**
     * One GET, no redirect following, never throws for a non-2xx status;
     * throws [IOException] for a connection problem.
     */
    fun open(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): HttpReply
}

/**
 * One reply to a GET. [contentLength] is -1 when the length is unknown.
 *
 * Closing the reply closes the body, and with it the connection behind it.
 */
internal class HttpReply(
    val status: Int,
    val location: String?,
    val contentType: String?,
    val contentLength: Long,
    val body: InputStream,
) : Closeable {
    override fun close() {
        body.close()
    }
}

/**
 * The real opener, built on the platform's [HttpURLConnection].
 *
 * It never follows a redirect, never uses a cache, and sends exactly the
 * headers it is given. For a status outside 2xx it reads the error stream, so
 * closing the reply releases the connection.
 */
internal class JdkHttpOpener : HttpOpener {
    override fun open(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): HttpReply {
        val opened = URL(url).openConnection()
        if (opened !is HttpURLConnection) {
            throw IOException("not an http connection")
        }
        val connection: HttpURLConnection = opened
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.doInput = true
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        for ((name, value) in headers) {
            connection.setRequestProperty(name, value)
        }
        try {
            val status = connection.responseCode
            val source = bodyOf(connection, status)
            return HttpReply(
                status = status,
                location = connection.getHeaderField("Location"),
                contentType = connection.contentType,
                contentLength = connection.contentLengthLong,
                body = ConnectionBody(source, connection),
            )
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private fun bodyOf(connection: HttpURLConnection, status: Int): InputStream {
        if (status in 200..299) {
            return connection.inputStream
        }
        val errors = connection.errorStream
        if (errors == null) {
            return ByteArrayInputStream(ByteArray(0))
        }
        return errors
    }
}

/** A body whose close also drops the connection it came from. */
private class ConnectionBody(
    source: InputStream,
    private val connection: HttpURLConnection,
) : FilterInputStream(source) {
    override fun close() {
        try {
            super.close()
        } finally {
            connection.disconnect()
        }
    }
}
