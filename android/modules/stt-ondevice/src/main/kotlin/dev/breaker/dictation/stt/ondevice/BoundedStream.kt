package dev.breaker.dictation.stt.ondevice

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Thrown by [BoundedStream] when more bytes than the limit have been read.
 */
internal class StreamLimitExceededException : IOException("the decompressed stream is longer than its limit")

/**
 * Counts the bytes that come out of [source] and refuses to pass the limit.
 *
 * Put it directly after a decompressor, so it counts DECOMPRESSED bytes: the
 * ones read and the ones skipped alike. It throws instead of ending the
 * stream early, so a caller cannot take a cut-off stream for a finished one.
 *
 * Exactly [maxBytes] bytes pass; the next byte makes it throw. It never asks
 * [source] for more than one byte beyond the limit, so a bomb is stopped
 * after one extra byte of work, not after a whole buffer. Once it has thrown
 * it throws on every later read or skip.
 *
 * Mark and reset are not supported (reset throws): a rewind would let the
 * same bytes be counted twice or not at all.
 *
 * @param source the stream to count.
 * @param maxBytes the most bytes allowed, zero or more.
 */
internal class BoundedStream(
    source: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(source) {
    private var count = 0L
    private var exceeded = false

    init {
        require(maxBytes >= 0L) { "the limit must not be negative" }
    }

    override fun read(): Int {
        failIfExceeded()
        val value = super.read()
        if (value >= 0) add(1L)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        failIfExceeded()
        val remaining = maxBytes - count
        val wanted = if (remaining < length) remaining.toInt() + 1 else length
        val got = super.read(buffer, offset, wanted)
        if (got > 0) add(got.toLong())
        return got
    }

    override fun skip(n: Long): Long {
        failIfExceeded()
        val remaining = maxBytes - count
        val wanted = if (remaining < n) remaining + 1L else n
        val skipped = super.skip(wanted)
        if (skipped > 0L) add(skipped)
        return skipped
    }

    override fun markSupported(): Boolean = false

    override fun reset() {
        throw IOException("mark and reset are not supported")
    }

    private fun add(bytes: Long) {
        count += bytes
        if (count > maxBytes) {
            exceeded = true
            throw StreamLimitExceededException()
        }
    }

    private fun failIfExceeded() {
        if (exceeded) throw StreamLimitExceededException()
    }
}
