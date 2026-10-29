package dev.breaker.dictation.updater

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** One call that the scripted opener received: the address and the two timeouts it was given. */
internal data class OpenCall(val url: String, val connectTimeoutMs: Int, val readTimeoutMs: Int)

/** A stream over [bytes] that records how many reads it served and whether it was closed. */
internal class TrackedStream(bytes: ByteArray) : InputStream() {
    private val source = ByteArrayInputStream(bytes)

    var readCalls: Int = 0
        private set

    var closed: Boolean = false
        private set

    override fun read(): Int {
        readCalls += 1
        return source.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        readCalls += 1
        return source.read(b, off, len)
    }

    override fun close() {
        closed = true
        source.close()
    }
}

/**
 * An opener that answers every call with the status, length and body the test sets, or throws the IOException the test sets.
 * It records each call's address and timeouts, and each reply it hands out.
 */
internal class ScriptedOpener(
    private val status: Int = 200,
    private val contentLength: Long = -1L,
    private val body: InputStream = ByteArrayInputStream(ByteArray(0)),
    private val failure: IOException? = null,
) : UpdateHttpOpener {
    val calls: MutableList<OpenCall> = mutableListOf()
    val replies: MutableList<UpdateHttpReply> = mutableListOf()

    override fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): UpdateHttpReply {
        calls.add(OpenCall(url, connectTimeoutMs, readTimeoutMs))
        val thrown = failure
        if (thrown != null) throw thrown
        val reply = UpdateHttpReply(status, contentLength, body)
        replies.add(reply)
        return reply
    }
}
