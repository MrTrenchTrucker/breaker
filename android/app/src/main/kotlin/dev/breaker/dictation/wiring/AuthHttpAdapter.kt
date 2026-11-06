package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport

// The three login limits: a login reply is a token and three wrapped keys (~1-2 KB), so 16
// KiB is ~8-16x headroom; a phone on a weak network must not be told its server is
// unreachable by a 5 s connect, and a loaded server can take more than 10 s to answer; one
// slow login beats a false error; the adapter takes no defaults, the caller supplies the limits.
internal const val AUTH_CONNECT_TIMEOUT_MS = 10_000
internal const val AUTH_READ_TIMEOUT_MS = 30_000
internal const val AUTH_MAX_REPLY_BYTES = 16_384L

/** Implements the account module's AuthHttp over the app's HttpTransport with no per-call state. */
internal class AuthHttpAdapter(
    private val transport: HttpTransport,
    private val limits: CallLimits,
) : AuthHttp {
    private fun methodOf(method: String): CallMethod = when (method) {
        "POST" -> CallMethod.POST
        "GET" -> CallMethod.GET
        else -> throw IllegalArgumentException("the method is neither GET nor POST")
    }

    override fun send(request: AuthHttpRequest): AuthHttpReply {
        val call = CallRequest(
            method = methodOf(request.method),
            url = request.url,
            headers = request.headers,
            body = request.body,
            limits = limits,
        )
        val reply = transport.send(call)
        return AuthHttpReply(reply.status, reply.body)
    }
}
