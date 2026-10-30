package dev.breaker.dictation.http

import java.io.IOException

/**
 * A rule of this module refused the request or the reply. It is an IOException, but it is not a
 * connection problem: callers catch HttpRefusal before IOException, because a refusal does not
 * mean that the server is unreachable.
 */
class HttpRefusal(
    val reason: HttpRefusalReason,
) : IOException(sentenceOf(reason))

/** Why the module refused a request or a reply. */
enum class HttpRefusalReason { NOT_HTTPS, BAD_HEADER, REPLY_TOO_LARGE, LENGTH_MISMATCH }

/** The fixed sentence of each reason. It never holds a header, a body or an address. */
private fun sentenceOf(reason: HttpRefusalReason): String =
    when (reason) {
        HttpRefusalReason.NOT_HTTPS -> "the address is not usable over https"
        HttpRefusalReason.BAD_HEADER -> "a header name or value is not allowed"
        HttpRefusalReason.REPLY_TOO_LARGE -> "the reply is larger than its cap"
        HttpRefusalReason.LENGTH_MISMATCH -> "the body ended before its announced length"
    }
