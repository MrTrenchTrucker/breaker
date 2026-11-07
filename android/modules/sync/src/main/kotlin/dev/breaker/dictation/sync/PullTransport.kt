package dev.breaker.dictation.sync

/** Sends one pull request and answers what came back.
 *
 * The app supplies the transport. [fetch] sends the request (path and query)
 * with the user's token. It answers [Answered] for any HTTP answer it received
 * (status and body text), and [Unreachable] when it could not reach the server
 * or the connection failed. It never throws for those: an exception from a
 * transport is a bug and propagates.
 *
 * All types here are internal: the app cannot implement an interface that
 * mentions an internal type yet. The wiring piece decides what is published.
 */
internal fun interface PullTransport {
    /** Send [request] and say what happened. */
    fun fetch(request: PullRequest): PullTransportResult
}

/** One answer from a transport. */
internal sealed class PullTransportResult {
    /** The server answered: [status] and [body] exactly as received. */
    class Answered(val status: Int, val body: String) : PullTransportResult()
    /** The server could not be reached or the connection failed. */
    object Unreachable : PullTransportResult()
}
