package dev.breaker.dictation.updater

import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.StreamReply

/** An [HttpTransport] that answers from [answer] and keeps every request it was given. */
internal class FakeTransport(private val answer: (CallRequest) -> BytesReply) : HttpTransport {
    val sent = ArrayList<CallRequest>()

    override fun send(request: CallRequest): BytesReply {
        sent.add(request)
        return answer(request)
    }

    override fun open(request: CallRequest): StreamReply = throw AssertionError("the check must not open a stream")
}

internal fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

/** The six fields as the server writes them; each argument is the JSON text of that member. */
internal fun manifestText(
    version: String = "\"0.0.0.1\"",
    versionCode: String = "2",
    apkUrl: String = "\"https://example.test/app.apk\"",
    sha256: String = "\"aaaa\"",
    signature: String = "\"bbbb\"",
    notes: String = "\"Latest release\"",
): String =
    "{\"version\":$version,\"version_code\":$versionCode,\"apk_url\":$apkUrl," +
        "\"sha256\":$sha256,\"signature\":$signature,\"notes\":$notes}"

internal const val NOT_FOUND_TEXT = "{\"error\":\"not_found\",\"message\":\"No release yet\"}"
