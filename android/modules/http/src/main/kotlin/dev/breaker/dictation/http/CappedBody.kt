package dev.breaker.dictation.http

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The body of a reply, read under its cap. A read that would pass the cap throws HttpRefusal with
 * REPLY_TOO_LARGE, and a body that ends before its announced length throws HttpRefusal with
 * LENGTH_MISMATCH. The source is asked for at most one byte past the cap, so a body is never read
 * far beyond it.
 *
 * Both calls read through this class: the send call reads it whole, and the open call hands it to the
 * caller. Closing it closes the source, and then runs the close action.
 */
internal class CappedBody(
    private val source: InputStream,
    private val cap: Long,
    private val announced: Long,
    private val onClose: () -> Unit,
) : InputStream() {
    private var served = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val room = cap - served
        if (room < 0L) throw HttpRefusal(HttpRefusalReason.REPLY_TOO_LARGE)
        val ask = if (length.toLong() <= room) length else (room + 1L).toInt()
        val count = source.read(buffer, offset, ask)
        if (count < 0) {
            if (announced >= 0L && served < announced) throw HttpRefusal(HttpRefusalReason.LENGTH_MISMATCH)
            return -1
        }
        if (served + count > cap) throw HttpRefusal(HttpRefusalReason.REPLY_TOO_LARGE)
        served += count
        return count
    }

    override fun close() {
        try {
            source.close()
        } finally {
            onClose()
        }
    }

    /** Reads the body to its end and returns the bytes. */
    fun readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val count = read(chunk, 0, chunk.size)
            if (count < 0) break
            out.write(chunk, 0, count)
        }
        return out.toByteArray()
    }

    private companion object {
        const val CHUNK_BYTES = 8192
    }
}
