package dev.breaker.dictation.http

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * The client built on the platform HttpURLConnection. It is the only transport of this module that
 * opens a connection.
 *
 * A refusal, HttpRefusal, is raised before any connection for an address or a header that breaks a
 * rule, and callers catch HttpRefusal before IOException, because a refusal is not an unreachable
 * server. Any other IOException is a connection problem.
 *
 * It never follows a redirect, never uses a cache, and sends a body with a fixed length. No header or
 * body reaches an exception message, a string form or a log.
 */
class JdkHttpTransport internal constructor(
    private val openConnection: (String) -> HttpURLConnection,
) : HttpTransport {

    /** The transport over the platform connection. */
    constructor() : this(::platformConnection)

    override fun send(request: CallRequest): BytesReply {
        val cap = request.limits.maxReplyBytes
        require(cap <= CallLimits.MAX_BYTES_REPLY) { SEND_CAP_MESSAGE }
        HttpRules.checkBeforeConnect(request)
        val connection = connect(request)
        try {
            val status = statusOf(connection)
            val announced = connection.contentLengthLong
            HttpRules.checkAnnouncedLength(announced, cap)
            val reader = CappedBody(sourceOf(connection, status), cap, announced) {}
            val bytes = reader.use { it.readAll() }
            return BytesReply(status, bytes)
        } finally {
            connection.disconnect()
        }
    }

    override fun open(request: CallRequest): StreamReply {
        HttpRules.checkBeforeConnect(request)
        val connection = connect(request)
        try {
            val status = statusOf(connection)
            val announced = connection.contentLengthLong
            val body = CappedBody(sourceOf(connection, status), request.limits.maxReplyBytes, announced) {
                connection.disconnect()
            }
            return StreamReply(status, connection.getHeaderField("Location"), connection.contentType, announced, body)
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    private fun connect(request: CallRequest): HttpURLConnection {
        val connection = openConnection(request.url)
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = request.method.name
            connection.connectTimeout = request.limits.connectTimeoutMs
            connection.readTimeout = request.limits.readTimeoutMs
            connection.doInput = true
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
            }
            connection.connect()
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            return connection
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    /** The status of [connection]. A value below 100 has no usable status line, so it is a connection problem. */
    private fun statusOf(connection: HttpURLConnection): Int {
        val status = connection.responseCode
        if (status < 100) throw IOException(NO_STATUS_MESSAGE)
        return status
    }

    /** A status from 200 to 399 is read from the input stream; any other status from the error stream, or an empty one. */
    private fun sourceOf(connection: HttpURLConnection, status: Int): InputStream {
        if (status in 200..399) return connection.inputStream
        return connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
    }
}

private const val SEND_CAP_MESSAGE = "the send call refuses a reply cap above its ceiling"
private const val NO_STATUS_MESSAGE = "the server sent no usable status line"

/** The platform connection for [url]. */
internal fun platformConnection(url: String): HttpURLConnection {
    val opened = try {
        URL(url).openConnection()
    } catch (malformed: MalformedURLException) {
        throw HttpRefusal(HttpRefusalReason.NOT_HTTPS)
    }
    if (opened !is HttpURLConnection) throw IOException("not an http connection")
    return opened
}
