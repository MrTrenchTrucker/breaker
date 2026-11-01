package dev.breaker.server.syncapi.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/** The shared body cap for the three unauthenticated auth routes (register, salt, login). */
internal const val MAX_AUTH_BODY_BYTES: Long = 4096L

/**
 * Reads a request body safely on an unauthenticated auth route.
 *
 * It answers 413 `payload_too_large` and returns null, before the body is parsed, when the declared
 * length is over the cap or when the stream holds more than the cap; a chunked body with no declared
 * length is caught by the stream check. It returns the body text (UTF-8) when it fits.
 */
internal suspend fun ApplicationCall.receiveAuthBody(): String? {
    val declared = request.contentLength()
    if (declared != null && declared > MAX_AUTH_BODY_BYTES) {
        respondText(
            """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}""",
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return null
    }

    val bytes = receiveChannel().readBuffer(MAX_AUTH_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_AUTH_BODY_BYTES) {
        respondText(
            """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}""",
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return null
    }

    return String(bytes, Charsets.UTF_8)
}
