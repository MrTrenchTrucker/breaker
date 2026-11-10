package dev.breaker.dictation.updater

import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.StreamReply
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A fake [HttpTransport] whose [open] answers from [stream] and records every request. */
internal class FakeOpenTransport(
    private val stream: (CallRequest) -> StreamReply,
) : HttpTransport {
    val opened = ArrayList<CallRequest>()

    override fun send(request: CallRequest): BytesReply = throw AssertionError("the download must not send")

    override fun open(request: CallRequest): StreamReply {
        opened.add(request)
        return stream(request)
    }
}

/** An InputStream over [bytes] that counts reads and can fail mid-read. */
internal class CountingStream(bytes: ByteArray) : InputStream() {
    private val source = ByteArrayInputStream(bytes)
    var reads = 0
        private set
    var served = 0L
    var failAfter: Long = Long.MAX_VALUE

    override fun read(): Int {
        reads++
        if (served >= failAfter) throw java.io.IOException("stream failed")
        return source.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        reads++
        val count = source.read(buffer, offset, length)
        if (count > 0) {
            served += count
            if (served >= failAfter) throw java.io.IOException("stream failed")
        }
        return count
    }
}

/** A stream that refuses with an HttpRefusal reason on read. */
internal class RefusalStream(private val reason: HttpRefusalReason) : InputStream() {
    override fun read(): Int = throw HttpRefusal(reason)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw HttpRefusal(reason)
}

/** Bytes as a stream that reports its length. */
internal fun streamOf(bytes: ByteArray, contentLength: Long = -1L): StreamReply =
    StreamReply(200, null, null, contentLength, CountingStream(bytes))

/** A tiny stream that counts up to [count] bytes without holding them in memory, then ends. */
internal fun countingStream(count: Long): InputStream = object : InputStream() {
    private var emitted = 0L
    override fun read(): Int = if (emitted++ < count) 65 else -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val remaining = count - emitted
        if (remaining <= 0) return -1
        val take = minOf(length.toLong(), remaining).toInt()
        for (i in 0 until take) buffer[offset + i] = 65
        emitted += take
        return take
    }
}

/** A stream that serves [first] once, then throws [failure] on every later read. */
internal class ThrowingStream(private val first: ByteArray, private val failure: Exception) : InputStream() {
    private var sent = false

    override fun read(): Int {
        if (sent) throw failure
        sent = true
        return first[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (sent) throw failure
        sent = true
        val take = minOf(length, first.size)
        first.copyInto(buffer, offset, 0, take)
        return take
    }
}

/** Streams [count] bytes like [countingStream] and records how many bytes the reader took. */
internal class MeteredStream(private val count: Long) : InputStream() {
    var emitted = 0L
        private set

    override fun read(): Int {
        if (emitted >= count) return -1
        emitted++
        return 65
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val remaining = count - emitted
        if (remaining <= 0) return -1
        val take = minOf(length.toLong(), remaining).toInt()
        for (i in 0 until take) buffer[offset + i] = 65
        emitted += take
        return take
    }
}

/** Wraps [inner] and records whether the reply closed it. */
internal class TrackedStream(private val inner: InputStream) : InputStream() {
    var closed = false
        private set

    override fun read(): Int = inner.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

    override fun close() {
        closed = true
        inner.close()
    }
}

/** A stream whose first read is interrupted and which is at its end for every later read. */
internal class InterruptedOnceStream : InputStream() {
    private var interrupted = false

    override fun read(): Int {
        if (interrupted) return -1
        interrupted = true
        throw java.io.InterruptedIOException("interrupted")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
}
