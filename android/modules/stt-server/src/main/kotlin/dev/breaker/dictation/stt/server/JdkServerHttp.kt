package dev.breaker.dictation.stt.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The real transport, built on the platform's HttpURLConnection.
 *
 * It never follows a redirect and never uses a cache, and it sends exactly the
 * headers it is given. The request body, when there is one, is sent with a fixed
 * length. The reply is read in full for a 2xx status and from the error stream
 * otherwise; an absent error stream is an empty body. A reply over
 * [MAX_REPLY_BYTES] is refused. A body that ends before its announced length is
 * an IOException. The connection is always released at the end.
 *
 * Nothing here logs, prints a header or puts a header value in an exception.
 * This class does not enforce https; the engine does that before it calls here.
 */
internal class JdkServerHttp(
    private val openConnection: (String) -> HttpURLConnection = ::openPlatformConnection,
) : ServerHttp {

    override fun exchange(request: ServerRequest, abort: AbortHandle): ServerReply {
        val connection = openConnection(request.url)
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = request.method
            connection.connectTimeout = request.connectTimeoutMs
            connection.readTimeout = request.readTimeoutMs
            connection.doInput = true
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
            }

            // Registered before connecting, so an abort at any point closes this connection.
            abort.onAbort { connection.disconnect() }

            try {
                connection.connect()
            } catch (e: Exception) {
                throw ConnectFailedException(e)
            }

            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }

            val reply = readReply(connection)

            if (abort.isAborted) {
                throw IOException("aborted")
            }
            return reply
        } finally {
            connection.disconnect()
        }
    }

    private fun readReply(connection: HttpURLConnection): ServerReply {
        val status = connection.responseCode
        if (status in 200..299) {
            return ServerReply(status, readCapped(connection.inputStream, connection.contentLengthLong))
        }
        // The JDK drops the error body of a 401 sent with a fixed-length body, but the Content-Length header stays.
        val errorBody = connection.errorStream ?: return ServerReply(status, ByteArray(0))
        return ServerReply(status, readCapped(errorBody, connection.contentLengthLong))
    }

    /**
     * Reads [source] to its end, refusing more than [MAX_REPLY_BYTES]. When the
     * server announced a length and fewer bytes arrived, the body ended early.
     */
    private fun readCapped(source: InputStream, declared: Long): ByteArray {
        source.use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (out.size() + count > MAX_REPLY_BYTES) {
                    throw ReplyTooLargeException()
                }
                out.write(chunk, 0, count)
            }
            if (declared >= 0 && out.size() < declared) {
                throw IOException("the body ended early")
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val CHUNK_BYTES = 8192
    }
}

/** The platform connection for [url]; the default opener of the transport. */
private fun openPlatformConnection(url: String): HttpURLConnection {
    val opened = URL(url).openConnection()
    if (opened !is HttpURLConnection) {
        throw IOException("not an http connection")
    }
    return opened
}
