package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.sync.OlderPullRequest
import dev.breaker.dictation.sync.OlderPullTransport
import dev.breaker.dictation.sync.PullTransportResult
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The app's [OlderPullTransport]: sends one older-rows request as `GET /v1/transcriptions/older`
 * over the app's [HttpTransport] and answers what came back.
 *
 * The http module's rule (android/modules/http/AGENTS.md): no header and no body reaches a log, a
 * print, an exception message or a string form. The token and the body text never appear in an
 * exception message, a toString or any log here.
 *
 * Constructor takes the transport, the limits and one supplier that returns the address and the
 * token of ONE call together (an addressed session). They are read ONCE per call and neither is
 * kept. Answers [PullTransportResult.Answered] for any HTTP answer (the status and the body text
 * exactly as received), and [PullTransportResult.Unreachable] when there is no session or the
 * address is blank, when the transport throws [HttpRefusal] or [IOException], or when the reply
 * body is not valid UTF-8. It never throws for those.
 *
 * Limits are chosen by the caller that wires this adapter (CallLimits has no defaults). Connect
 * 10_000 ms and read 30_000 ms as the sync uploader uses, and a reply cap of 5_242_880 bytes for
 * an older-rows page.
 */
internal class OlderRowsTransport(
    private val transport: HttpTransport,
    private val limits: CallLimits,
    private val addressedSession: () -> AddressedSession?,
) : OlderPullTransport {

    override fun fetch(request: OlderPullRequest): PullTransportResult = fetchPath(request.path(), request.query())

    /**
     * The whole adapter over plain texts. An [OlderPullRequest] can only be built inside the sync
     * module, so [fetch] hands over its path and its query and the tests drive this function.
     */
    internal fun fetchPath(path: String, query: String): PullTransportResult {
        val session = addressedSession()
        if (session == null || session.base.isBlank()) {
            return PullTransportResult.Unreachable
        }
        val url = if (query.isEmpty()) session.base + path else session.base + path + "?" + query
        val call = CallRequest(
            method = CallMethod.GET,
            url = url,
            headers = listOf("Authorization" to "Bearer " + session.session.token),
            body = null,
            limits = limits,
        )
        return try {
            val reply = transport.send(call)
            PullTransportResult.Answered(reply.status, decode(reply.body))
        } catch (refusal: HttpRefusal) {
            // A rule of the http module refused the request or the reply. The older-rows read has
            // no other answer for that: it stops and is tried again later.
            PullTransportResult.Unreachable
        } catch (failure: IOException) {
            // A connection problem, or a body that is not UTF-8 (CharacterCodingException).
            PullTransportResult.Unreachable
        }
    }

    /** UTF-8, strict: a bad byte throws instead of turning into U+FFFD text for the page decoder. */
    private fun decode(body: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
}
