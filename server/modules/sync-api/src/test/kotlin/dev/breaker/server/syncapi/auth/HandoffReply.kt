package dev.breaker.server.syncapi.auth

import io.ktor.http.HttpStatusCode

/** One answer of a route under test: the status and the whole body text, read while the test application was open. */
class Reply(val status: HttpStatusCode, val body: String)

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** The same bytes as [canonical] written with a non-zero spare bit in the last data character. */
internal fun nonCanonicalOf(canonical: String): String {
    val last = canonical.length - 2
    val index = BASE64_ALPHABET.indexOf(canonical[last])
    return canonical.substring(0, last) + BASE64_ALPHABET[index + 1] + "="
}
