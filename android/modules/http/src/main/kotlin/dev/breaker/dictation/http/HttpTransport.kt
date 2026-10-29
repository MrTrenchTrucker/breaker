package dev.breaker.dictation.http

import java.io.IOException

/**
 * The one client for calls to the Local Server and the update host.
 *
 * Both calls throw [IOException] for a connection problem. A refusal, [HttpRefusal], is an
 * IOException too, so callers catch HttpRefusal before IOException: a refusal means that the
 * request or the reply broke a rule of this module, and it is not evidence that the server
 * is unreachable.
 */
interface HttpTransport {
    /** Sends [request] and reads the whole reply into memory, under the reply cap the request names. */
    fun send(request: CallRequest): BytesReply

    /** Sends [request] and returns the reply with its body still to be read. Close the reply when done. */
    fun open(request: CallRequest): StreamReply
}
