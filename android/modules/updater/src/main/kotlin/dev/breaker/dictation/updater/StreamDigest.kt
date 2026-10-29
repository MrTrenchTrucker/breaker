package dev.breaker.dictation.updater

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The outcome of a streamed digest check. */
internal sealed class StreamDigestResult {
    /** The digest of the stream equals the expected digest; [byteCount] is the number of bytes read. */
    internal data class Matched(val byteCount: Long) : StreamDigestResult()

    /** The digest of the stream differs from the expected digest; [byteCount] is the number of bytes read. */
    internal data class Mismatched(val byteCount: Long) : StreamDigestResult()

    /** The stream holds more bytes than the cap. */
    internal object TooLarge : StreamDigestResult()

    /** The stream could not be read, or returned no bytes for a request. */
    internal object Unreadable : StreamDigestResult()

    /** The expected digest is not 64 lowercase hexadecimal characters; no byte was read. */
    internal object ExpectedMalformed : StreamDigestResult()
}

/** The size of each read from the stream. */
private const val CHUNK_BYTES: Int = 64 * 1024

/**
 * Computes the SHA-256 digest of [input] in chunks, and compares it with [expectedSha256].
 *
 * The stream is read only as far as the cap: no read asks for more than one byte past [capBytes], and the
 * function stops as soon as the count passes it. The caller closes [input]; this function never closes it.
 * An expected digest that is not 64 lowercase hexadecimal characters is refused before any byte is read.
 *
 * @param capBytes the largest number of bytes accepted; it must not be negative.
 * @throws IllegalArgumentException when [capBytes] is negative.
 */
internal fun verifyStreamDigest(input: InputStream, expectedSha256: String, capBytes: Long): StreamDigestResult {
    require(capBytes >= 0) { "A byte cap cannot be negative" }
    if (!isLowerSha256Hex(expectedSha256)) return StreamDigestResult.ExpectedMalformed
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(CHUNK_BYTES)
    var count = 0L
    try {
        while (true) {
            val room = capBytes - count
            val want = if (room >= CHUNK_BYTES) CHUNK_BYTES else (room + 1).toInt()
            val read = input.read(buffer, 0, want)
            if (read == -1) break
            if (read == 0) return StreamDigestResult.Unreadable
            count += read
            if (count > capBytes) return StreamDigestResult.TooLarge
            digest.update(buffer, 0, read)
        }
    } catch (e: IOException) {
        return StreamDigestResult.Unreadable
    }
    val actual = toLowerHex(digest.digest())
    return if (actual == expectedSha256) StreamDigestResult.Matched(count) else StreamDigestResult.Mismatched(count)
}
