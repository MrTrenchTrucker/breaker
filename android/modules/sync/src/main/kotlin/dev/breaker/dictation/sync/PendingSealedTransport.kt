package dev.breaker.dictation.sync

/**
 * Fetches the owner's still-sealed agent results and answers what came back.
 *
 * The app supplies the transport. [fetch] sends GET /v1/sync/pending-sealed with the user's token
 * and answers [PullTransportResult.Answered] for any HTTP answer it received (status and body text)
 * and [PullTransportResult.Unreachable] when the server could not be reached or the connection
 * failed. It never throws for those: an exception from a transport is a bug and propagates.
 *
 * The app reads at most 8 MiB of response text; the contract constant is
 * [PendingSealedDecoder.MAX_BODY_CHARS]. The server has no pagination and no limit on this list.
 */
fun interface PendingSealedTransport {
    /** Send the request and say what happened. */
    fun fetch(): PullTransportResult
}
