package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpTransport
import java.io.IOException

/**
 * The service-status ask over the app's [HttpTransport]: `GET /v1/status` with the bearer of one
 * addressed session.
 *
 * The http module's rule (android/modules/http/AGENTS.md:19): no header and no body reaches a log,
 * a print, an exception message or a string form. The bearer is built into the header and appears in
 * nothing else. The address and the token are read ONCE, from one [AddressedSession], and neither is
 * kept on the object.
 *
 * Every outcome that is not a 200 carrying a parseable body is [ServiceStatusAnswer.Failed]: no
 * session, a blank address, an [HttpRefusal] (the reply over its cap is REPLY_TOO_LARGE), an
 * [IOException] (the server unreachable), a status that is not 200 (signed out, the token store
 * failing), or a body that does not parse. The ask never throws, so the schedule that calls it cannot
 * be crashed by a server.
 */
internal class ServiceStatusProbe(
    private val transport: HttpTransport,
    private val addressedSession: () -> AddressedSession?,
) : ServiceStatusAsker {

    override fun ask(): ServiceStatusAnswer {
        val session = addressedSession() ?: return ServiceStatusAnswer.Failed
        if (session.base.isBlank()) return ServiceStatusAnswer.Failed
        val call = CallRequest(
            method = CallMethod.GET,
            url = session.base.removeSuffix("/") + STATUS_PATH,
            headers = listOf("Authorization" to "Bearer " + session.session.token),
            body = null,
            limits = CallLimits(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, MAX_REPLY_BYTES),
        )
        val reply: BytesReply = try {
            transport.send(call)
        } catch (_: HttpRefusal) {
            // The module refused the request or the reply: a broken rule, not a dead server.
            return ServiceStatusAnswer.Failed
        } catch (_: IOException) {
            // A connection problem: the server could not be reached.
            return ServiceStatusAnswer.Failed
        }
        if (reply.status != STATUS_OK) return ServiceStatusAnswer.Failed
        val body = ServiceStatusJson.decode(reply.body) ?: return ServiceStatusAnswer.Failed
        return ServiceStatusAnswer.Answered(body)
    }

    private companion object {
        /** The connect timeout of the ask, in milliseconds: short, the ask is a quick foreground check. */
        const val CONNECT_TIMEOUT_MS: Int = 1_500

        /** The read timeout of the ask, in milliseconds. */
        const val READ_TIMEOUT_MS: Int = 5_000

        /** The reply cap of the ask, in bytes: a names-and-booleans reply is tiny. */
        const val MAX_REPLY_BYTES: Long = 4_096L

        /** The status path of the Local Server. */
        const val STATUS_PATH: String = "/v1/status"

        /** The one status whose body is worth reading. */
        const val STATUS_OK: Int = 200
    }
}
