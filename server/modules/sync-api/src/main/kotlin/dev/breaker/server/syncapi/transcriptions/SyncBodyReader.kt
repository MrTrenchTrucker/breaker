package dev.breaker.server.syncapi.transcriptions

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

// The body cap of POST /v1/sync: 1,048,576 bytes. The exact limit is accepted; one byte more is 413.
internal const val SYNC_BODY_CAP_BYTES: Long = 1024L * 1024L

// Reads the body of POST /v1/sync. It answers 413 and returns null, before any parsing, when the
// declared length is over the cap or when the stream holds more than the cap (a chunked body has no
// declared length). Otherwise it returns the body text as UTF-8.
internal suspend fun ApplicationCall.receiveSyncBody(): String? {
    val declared = request.contentLength()
    if (declared != null && declared > SYNC_BODY_CAP_BYTES) {
        respondBodyTooLarge()
        return null
    }

    val bytes = receiveChannel().readBuffer(SYNC_BODY_CAP_BYTES + 1).readByteArray()
    if (bytes.size > SYNC_BODY_CAP_BYTES) {
        respondBodyTooLarge()
        return null
    }

    return String(bytes, Charsets.UTF_8)
}
