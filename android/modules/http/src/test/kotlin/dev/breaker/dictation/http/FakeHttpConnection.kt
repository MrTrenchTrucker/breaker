package dev.breaker.dictation.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val FAKE_URL = "https://example.test/fake-path"
private const val SAFETY_LIMIT = 1_048_576L

/**
 * A scripted connection for the client tests. It is a real HttpURLConnection, so the values the client
 * sets are kept by the base class; the methods below answer from the script. No socket is opened.
 */
internal class FakeHttpConnection(
    private val status: Int,
    private val announced: Long,
    private val body: InputStream,
) : HttpURLConnection(URL(FAKE_URL)) {
    var errorBody: InputStream? = null
    var location: String? = null
    var connectFails = false
    val sentBody = ByteArrayOutputStream()
    var fixedLength = -1
    var connects = 0
    var disconnects = 0

    override fun connect() {
        connects += 1
        if (connectFails) throw IOException("the connection could not be made")
    }

    override fun disconnect() {
        disconnects += 1
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = status

    override fun getContentLengthLong(): Long = announced

    override fun getInputStream(): InputStream = body

    override fun getErrorStream(): InputStream? = if (status >= 400) errorBody else null

    override fun getHeaderField(name: String?): String? = if (name == "Location") location else null

    override fun getContentType(): String? = "application/octet-stream"

    override fun getOutputStream(): OutputStream = sentBody

    override fun setFixedLengthStreamingMode(contentLength: Int) {
        fixedLength = contentLength
    }
}

/** A body of fixed bytes that counts its reads and records its close. */
internal class CountingBody(private val bytes: ByteArray) : InputStream() {
    var reads = 0
    var closed = false
    private var position = 0

    override fun read(): Int {
        reads += 1
        if (position >= bytes.size) return -1
        val value = bytes[position].toInt() and 0xFF
        position += 1
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        reads += 1
        if (length == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        return count
    }

    override fun close() {
        closed = true
    }
}

/** A body that never ends. It fails by name when it is read past its safety limit. */
internal class EndlessBody : InputStream() {
    var served = 0L

    override fun read(): Int = throw AssertionError("an endless body was read one byte at a time")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (served >= SAFETY_LIMIT) throw AssertionError("an endless body was read past its safety limit")
        java.util.Arrays.fill(buffer, offset, offset + length, 7.toByte())
        served += length
        return length
    }
}

/** What the connection seam was asked to open, in order. */
internal class SeamRecord {
    var opened = 0
    val urls = ArrayList<String>()
}

/** A client whose connection seam hands out [connection] and records each opening in [record]. */
internal fun transportOver(connection: FakeHttpConnection, record: SeamRecord): JdkHttpTransport {
    val seam: (String) -> HttpURLConnection = { url ->
        record.opened += 1
        record.urls.add(url)
        connection
    }
    return JdkHttpTransport(seam)
}

/** Limits with the two timeouts the tests do not study and the given reply cap. */
internal fun limitsOf(cap: Long): CallLimits =
    CallLimits(connectTimeoutMs = 1_500, readTimeoutMs = 5_000, maxReplyBytes = cap)

/** The refusal that [call] throws, or null when [call] returns. Any other exception propagates. */
internal fun refusedWith(call: () -> Unit): HttpRefusal? =
    try {
        call()
        null
    } catch (refusal: HttpRefusal) {
        refusal
    }

/** The IOException that [call] throws, or null when [call] returns. Any other exception propagates. */
internal fun failureOf(call: () -> Unit): IOException? =
    try {
        call()
        null
    } catch (failure: IOException) {
        failure
    }

/** The IllegalArgumentException that [call] throws, or null when [call] returns. */
internal fun argumentRefusalOf(call: () -> Unit): IllegalArgumentException? =
    try {
        call()
        null
    } catch (refused: IllegalArgumentException) {
        refused
    }

/** Reads [stream] to its end and returns the bytes. */
internal fun readAllOf(stream: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
