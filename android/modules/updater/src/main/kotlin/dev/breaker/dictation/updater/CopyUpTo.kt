package dev.breaker.dictation.updater

import java.io.IOException
import java.io.InputStream

/** The size of each read. */
private const val COPY_CHUNK_BYTES: Int = 64 * 1024

/**
 * Copies at most [limitBytes] bytes from [input] into [sink] and returns how many were copied.
 *
 * The last read asks only for the bytes that are left under the limit, so a longer input is never read past it. The result is null
 * when a read or a sink call throws an IOException, or when a read returns zero for a request of one or more bytes. The caller
 * closes [input]; this function never closes it.
 */
internal fun copyUpTo(input: InputStream, limitBytes: Long, sink: (ByteArray, Int, Int) -> Unit): Long? {
    val buffer = ByteArray(COPY_CHUNK_BYTES)
    var total = 0L
    var room = limitBytes
    while (room > 0L) {
        val want = if (room >= COPY_CHUNK_BYTES) COPY_CHUNK_BYTES else room.toInt()
        val read = try { input.read(buffer, 0, want) } catch (error: IOException) { return null }
        if (read == -1) return total
        if (read == 0) return null
        total += read
        try { sink(buffer, 0, read) } catch (error: IOException) { return null }
        room = limitBytes - total
    }
    return total
}
