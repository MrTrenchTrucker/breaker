package dev.breaker.server.syncapi.sync

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/** The most bytes a push body may hold on the wire. A body over this is answered 413 and not stored. */
internal const val MAX_RAW_BYTES: Int = 131072

/** The most bytes the decoded `encrypted` text may hold. Checked before the content check. */
internal const val MAX_DECODED_BYTES: Long = 65536L

/**
 * The number of bytes a standard base64 text decodes to, computed from its length and padding alone.
 * For a padded or unpadded standard base64 text this is the decoded byte count.
 */
internal fun decodedByteCount(encrypted: String): Long {
    val unpadded = encrypted.length - encrypted.takeLastWhile { it == '=' }.length
    return (unpadded * 3L) / 4
}

/**
 * Reads the request body and gives up once it holds more than [MAX_RAW_BYTES] bytes.
 *
 * Relies on two calls: `io.ktor.server.request.receiveChannel` (ktor-server-core), which returns an
 * `io.ktor.utils.io.ByteReadChannel`, and `ByteReadChannel.readAvailable(dst, offset, length)`,
 * which returns the number of bytes read and -1 once the channel is closed. The buffer holds one
 * byte more than the limit, so a body that fills it is over the limit. Returns null for an
 * over-limit body; the bytes past the limit are not read.
 */
internal suspend fun readBoundedBody(call: ApplicationCall): ByteArray? {
    val channel: ByteReadChannel = call.receiveChannel()
    val buffer = ByteArray(MAX_RAW_BYTES + 1)
    var total = 0
    while (total <= MAX_RAW_BYTES) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    if (total > MAX_RAW_BYTES) return null
    return buffer.copyOf(total)
}
