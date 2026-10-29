package dev.breaker.dictation.updater

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens one HTTP GET and hands back the reply. This is the only main file that names the platform's network classes;
 * everything above it runs against a scripted opener in the JVM tests.
 *
 * The seam has no headers parameter: the platform opener applies only the list that [HttpRules.requestHeaders] returns, which is empty.
 */
internal fun interface UpdateHttpOpener {
    /** One GET with no redirect following. A non-2xx status is a reply, not an exception; a connection problem throws [IOException]. */
    fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): UpdateHttpReply
}

/** One reply. Closing the reply closes the body, and with it the connection behind it. */
internal class UpdateHttpReply(
    val status: Int,
    val contentLength: Long,
    val body: InputStream,
) : Closeable {
    override fun close() {
        body.close()
    }
}

/** The platform opener. It never follows a redirect, never uses a cache and reads no error body. */
internal class JdkUpdateHttpOpener : UpdateHttpOpener {
    override fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): UpdateHttpReply {
        val opened = URL(url).openConnection()
        if (opened !is HttpURLConnection) throw IOException("not an http connection")
        val connection: HttpURLConnection = opened
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.doInput = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        for (header in HttpRules.requestHeaders()) {
            connection.setRequestProperty(header.first, header.second)
        }
        try {
            val status = connection.responseCode
            val body: InputStream =
                if (HttpRules.isSuccessStatus(status)) connection.inputStream else ByteArrayInputStream(ByteArray(0))
            return UpdateHttpReply(status, connection.contentLengthLong, ConnectionBody(body, connection))
        } catch (error: IOException) {
            connection.disconnect()
            throw error
        }
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
