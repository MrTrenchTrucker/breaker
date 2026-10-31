package dev.breaker.dictation.format

import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.StreamReply
import dev.breaker.shared.prompts.FormatPrompt

internal val prompt = FormatPrompt(
    id = "t",
    version = "t",
    systemPrompt = (FormatPrompt.REQUIRED_CLAUSES + FormatPrompt.NEVER_CLAUSES).joinToString(" ")
)

internal class CountingFallback : Formatter {
    var calls = 0
    override fun format(rawText: String): String { calls++; return "fallback:$rawText" }
}

internal class FakeTransport : HttpTransport {
    val requests = mutableListOf<CallRequest>()
    var reply: BytesReply? = null
    var throwOnSend: Throwable? = null
    override fun send(request: CallRequest): BytesReply {
        requests.add(request)
        throwOnSend?.let { throw it }
        return reply ?: BytesReply(200, byteArrayOf())
    }
    override fun open(request: CallRequest): StreamReply {
        throw UnsupportedOperationException("open unused")
    }
}
