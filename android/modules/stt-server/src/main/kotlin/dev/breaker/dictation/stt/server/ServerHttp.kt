package dev.breaker.dictation.stt.server

import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Largest reply body the transport reads. A reply over this is refused, never cut short. */
internal const val MAX_REPLY_BYTES: Int = 4 * 1024 * 1024

/** Random bytes behind a multipart boundary: 16 bytes is 128 bits. */
private const val BOUNDARY_RANDOM_BYTES = 16

/**
 * One request as the transport sends it.
 *
 * The headers carry the API key, so [toString] prints only the method and the
 * size of the body. The URL and the headers never reach a log through this type.
 */
internal class ServerRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
) {
    override fun toString(): String = "ServerRequest(method=$method, bodyBytes=${body?.size ?: 0})"
}

/** One answer from the server: the status and the whole body, already read and no larger than [MAX_REPLY_BYTES]. */
internal class ServerReply(val status: Int, val body: ByteArray)

/**
 * A reply body was larger than [MAX_REPLY_BYTES].
 *
 * This is deliberately not an IOException, so that the handlers for transport
 * trouble never treat an oversize answer as a dropped connection.
 */
internal class ReplyTooLargeException : Exception("reply too large")

/**
 * The connection could not be made: refused, unknown host, connect timeout or a
 * failed TLS handshake. The cause is kept for classification and never printed.
 */
internal class ConnectFailedException(cause: Throwable?) : IOException("connect failed", cause)

/**
 * Stops one exchange from another thread and runs the registered action once.
 *
 * Only atomics are used, so [abort] and [onAbort] can race safely. Whichever of
 * the two sees the other's write runs the action, and the action is taken out of
 * its slot before it runs, so it never runs twice. Registering again before the
 * first abort replaces the pending action.
 */
internal class AbortHandle {
    private val aborted = AtomicBoolean(false)
    private val pending = AtomicReference<(() -> Unit)?>(null)

    val isAborted: Boolean
        get() = aborted.get()

    /** Marks the exchange as aborted. Calling it again does nothing. */
    fun abort() {
        aborted.set(true)
        pending.getAndSet(null)?.invoke()
    }

    /** Runs [action] now if the exchange is already aborted, else when [abort] is called. */
    fun onAbort(action: () -> Unit) {
        pending.set(action)
        if (aborted.get()) {
            pending.getAndSet(null)?.invoke()
        }
    }
}

/**
 * One blocking request and reply over the network.
 *
 * Transport trouble is thrown as the IOException family: [ConnectFailedException]
 * for the connect phase, a SocketTimeoutException for a read timeout. An oversize
 * reply throws [ReplyTooLargeException]. Redirects are never followed.
 */
internal fun interface ServerHttp {
    fun exchange(request: ServerRequest, abort: AbortHandle): ServerReply
}

/**
 * Supplies the boundary text for one multipart body.
 *
 * [RANDOM] returns 128 random bits as 32 lower-case hex characters, from a
 * secure random source, so a boundary cannot be guessed from the body.
 */
internal fun interface BoundarySource {
    fun next(): String

    companion object {
        private val secure = SecureRandom()

        val RANDOM: BoundarySource = BoundarySource {
            val bytes = ByteArray(BOUNDARY_RANDOM_BYTES)
            secure.nextBytes(bytes)
            bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}
