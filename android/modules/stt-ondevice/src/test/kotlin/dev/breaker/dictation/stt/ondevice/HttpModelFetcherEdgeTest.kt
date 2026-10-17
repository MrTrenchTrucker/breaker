package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

/**
 * Edge cases of the real fetcher that the other fetcher tests do not pin: defaults, address
 * spelling, what a failing stream does, and the public constructor. Nothing here opens a socket.
 */
class HttpModelFetcherEdgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    /** An address the policy refuses before any request, so the public constructor never reaches the network. */
    private val unreachable = "http://127.0.0.1:1/x"

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    private fun noPartial() = assertEquals("no file is left in staging", emptyList<String>(), stagedNames(staging))

    /** An output that logs flush and close, writes to a real file, and can fail on flush. */
    private class ObservedOutput(
        private val file: File,
        private val events: MutableList<String>,
        private val failFlush: Boolean,
    ) : OutputStream() {
        private val real = FileOutputStream(file)

        var existedAtFlush: Boolean = false

        override fun write(b: Int) { real.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { real.write(b, off, len) }
        override fun flush() {
            events.add("flush")
            existedAtFlush = file.exists()
            if (failFlush) throw IOException("flush failed")
            real.flush()
        }
        override fun close() {
            events.add("close")
            real.close()
        }
    }

    /** A body that reads from [inner] and throws an IOException when it is closed. */
    private class CloseFails(private val inner: InputStream) : ProbeStream() {
        override fun fill(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

        override fun close() {
            super.close()
            throw IOException("close failed")
        }
    }

    // ---- limits and address policy ----

    @Test
    fun `the default total time limit is one hour`() {
        assertEquals("the default total time limit in milliseconds", 3_600_000L, DownloadLimits().totalTimeoutMillis)
    }

    private fun fetchWithClockJump(jump: Long): Attempt {
        val clock = FakeClock()
        val body = HookStream(3000, 1000) { if (it == 1) clock.advance(jump) }
        val opener = FakeOpener(serve(reply(body = body, contentLength = 3000)))
        return attemptModel(fetcherOf(opener, clock = clock), testEntry(), staging)
    }

    @Test
    fun `with the default limits a fetch is inside the total time just under an hour and out of it just over`() {
        val inside = fetchWithClockJump(3_599_999L)
        assertEquals("a download taking just under an hour is kept", 3000L, inside.fetchedFile("just under an hour").length())
        val outside = fetchWithClockJump(3_600_001L)
        assertEquals("timed out", outside.failedReason("just over an hour"))
        noPartial()
    }

    @Test
    fun `an upper-case scheme is accepted on the first hop and on a redirect`() {
        val limits = DownloadLimits()
        assertTrue("policy: upper-case scheme on the first hop", limits.isAllowed(URI("HTTPS://api.github.com/x"), 0))
        assertTrue("policy: upper-case scheme on a redirect hop", limits.isAllowed(URI("HTTPS://release-assets.githubusercontent.com/x"), 1))
        val first = FakeOpener(serve(okBytes(ByteArray(10) { 1 })))
        attemptModel(fetcherOf(first), testEntry(url = "HTTPS://api.github.com/x"), staging)
            .fetchedFile("a first address with an upper-case scheme")
        assertEquals("the first request was made", 1, first.requests.size)
        val hop = FakeOpener(serve(redirectTo("HTTPS://release-assets.githubusercontent.com/x")), serve(okBytes(ByteArray(10) { 1 })))
        attemptModel(fetcherOf(hop), testEntry(), staging).fetchedFile("a redirect to an upper-case scheme")
        assertEquals("the redirect was followed", 2, hop.requests.size)
    }

    @Test
    fun `an address with no host is refused without a request and the reason carries no address`() {
        for (address in listOf("https:///x", "https:x")) {
            val opener = FakeOpener(script = emptyList())
            val reason = attemptModel(fetcherOf(opener), testEntry(url = address), staging).failedReason("first address $address")
            assertTrue("first address $address: reason was '$reason'", reason.startsWith("address refused"))
            assertFalse("first address $address: the reason carries a path or the address", reason.contains("/") || reason.contains(address))
            assertEquals("first address $address: no request", 0, opener.requests.size)
        }
        val hop = FakeOpener(serve(redirectTo("https:///x")))
        val reason = attemptModel(fetcherOf(hop), testEntry(), staging).failedReason("a redirect without a host")
        assertTrue("redirect without a host: reason was '$reason'", reason.startsWith("redirect refused"))
        assertFalse("redirect without a host: the reason carries a path", reason.contains("/"))
        assertEquals("redirect without a host: only the first request", 1, hop.requests.size)
    }

    @Test
    fun `a refused first address is named by its lower-case host alone`() {
        val query = "sig=${HttpFixtures.SECRET}"
        val addresses = listOf(
            "https://user@API.GitHub.com/path/x?$query",
            "http://API.GitHub.com/path/x?$query",
            "https://API.GitHub.com:8443/a/b?$query",
        )
        for (address in addresses) {
            val opener = FakeOpener(script = emptyList())
            val reason = attemptModel(fetcherOf(opener), testEntry(url = address), staging).failedReason("first address $address")
            assertEquals("first address $address", "address refused: api.github.com", reason)
            assertEquals("first address $address: no request", 0, opener.requests.size)
        }
    }

    // ---- reply checks ----

    @Test
    fun `a content type with leading white space before application json is refused`() {
        for (type in listOf(" application/json", "\tapplication/json; charset=utf-8", "  APPLICATION/JSON")) {
            val reason = attemptModel(fetcherOf(FakeOpener(serve(reply(body = SizedStream(10), contentType = type)))), testEntry(), staging)
                .failedReason("content type '$type'")
            assertEquals("content type '$type'", "unexpected content type", reason)
        }
        val plain = reply(body = SizedStream(10), contentType = " application/octet-stream")
        attemptModel(fetcherOf(FakeOpener(serve(plain))), testEntry(), staging).fetchedFile("a binary type with leading white space")
    }

    @Test
    fun `a declared length of zero needs no free space and the empty body is the failure`() {
        val opener = FakeOpener(serve(reply(body = BytesStream(ByteArray(0)), contentLength = 0L)))
        val outcome = attemptModel(fetcherOf(opener, usableSpace = { 1L }), testEntry(), staging)
        assertEquals("empty", outcome.failedReason("a declared length of zero with one byte of free space"))
        noPartial()
    }

    // ---- the staging file and its output stream ----

    @Test
    fun `the output is flushed before it is closed`() {
        val events = ArrayList<String>()
        val open: (File) -> OutputStream = { ObservedOutput(it, events, false) }
        val opener = FakeOpener(serve(okBytes(ByteArray(100) { 1 })))
        attemptModel(fetcherOf(opener, openOutput = open), testEntry(), staging).fetchedFile("a plain download")
        val flushAt = events.indexOf("flush")
        assertTrue("the output was flushed (events $events)", flushAt >= 0)
        assertTrue("the flush came before the first close (events $events)", events.indexOf("close") > flushAt)
    }

    @Test
    fun `a flush that fails is thrown as an IOException and the partial file is deleted`() {
        val events = ArrayList<String>()
        var made: ObservedOutput? = null
        val open: (File) -> OutputStream = { file -> ObservedOutput(file, events, true).also { made = it } }
        val opener = FakeOpener(serve(okBytes(ByteArray(100) { 1 })))
        attemptModel(fetcherOf(opener, openOutput = open), testEntry(), staging).thrownIo("a flush failure")
        val output = made ?: throw AssertionError("the output was never opened")
        assertTrue("control: the partial file existed when the flush failed", output.existedAtFlush)
        noPartial()
    }

    private fun outputClosedAfter(name: String, expected: String, body: InputStream, contentLength: Long = -1L, cancelled: () -> Boolean = { false }) {
        val outputs = RecordingOutputs()
        val opener = FakeOpener(serve(reply(body = body, contentLength = contentLength)))
        val outcome = attemptModel(fetcherOf(opener, cancelled = cancelled, openOutput = outputs::open), testEntry(), staging)
        assertEquals("$name: the failure", expected, outcome.failedReason(name))
        assertEquals("control: $name started a partial file", 1, outputs.files.size)
        assertTrue("$name: the output stream was closed", outputs.closes > 0)
        noPartial()
    }

    @Test
    fun `the output stream is closed when the copy ends in a failure`() {
        val cancel = CancelFlag()
        outputClosedAfter("a body past the cap", "too large", SizedStream(HttpFixtures.CAP_1MB + 1))
        outputClosedAfter("a read that fails midway", "network: IOException", FailingStream(5000, IOException("cut")), contentLength = 9000)
        outputClosedAfter("a cancel during the copy", "cancelled", HookStream(100_000) { if (it == 2) cancel.on = true }, cancelled = cancel.check)
    }

    @Test
    fun `a failure to open the staging file is thrown as an IOException and leaves nothing behind`() {
        val denied = IOException("denied")
        val open: (File) -> OutputStream = { throw denied }
        val body = CloseCounter(BytesStream(ByteArray(10) { 1 }))
        val modelOpener = FakeOpener(serve(reply(body = body, contentLength = 10)))
        val thrown = attemptModel(fetcherOf(modelOpener, openOutput = open), testEntry(), staging).thrownIo("a model whose staging file cannot be opened")
        assertSame("the original exception is the one thrown", denied, thrown)
        assertTrue("the body was closed", body.closes > 0)
        val listOpener = FakeOpener(serve(okBytes(ByteArray(10) { 1 })))
        attemptChecksums(fetcherOf(listOpener, openOutput = open), staging).thrownIo("a checksum list whose staging file cannot be opened")
        noPartial()
    }

    // ---- a reply that fails to close ----

    @Test
    fun `a reply whose close fails changes neither a result nor a reason`() {
        val bytes = ByteArray(300) { 1 }
        val ok = reply(200, CloseFails(BytesStream(bytes)), contentLength = 300L)
        val file = attemptModel(fetcherOf(FakeOpener(serve(ok))), testEntry(), staging).fetchedFile("a download whose reply close fails")
        assertEquals("the whole body was kept", 300L, file.length())
        val notFound = reply(404, CloseFails(BytesStream(ByteArray(0))))
        assertEquals("http 404", attemptModel(fetcherOf(FakeOpener(serve(notFound))), testEntry(), staging).failedReason("a 404 whose reply close fails"))
        val hop = reply(302, CloseFails(BytesStream(ByteArray(0))), location = HttpFixtures.RELEASE_URL, contentType = null)
        val opener = FakeOpener(serve(hop), serve(okBytes(bytes)))
        attemptModel(fetcherOf(opener), testEntry(), staging).fetchedFile("a redirect whose reply close fails")
        assertEquals("the redirect was followed", 2, opener.requests.size)
    }

    // ---- the public constructor ----

    @Test
    fun `the public constructor does not cancel by default`() {
        val reason = attemptModel(HttpModelFetcher(), testEntry(url = unreachable), staging).failedReason("a default fetcher")
        assertEquals("address refused: 127.0.0.1", reason)
        noPartial()
    }

    @Test
    fun `the public constructor uses the limits it is given`() {
        val limits = DownloadLimits(totalTimeoutMillis = -1L)
        val reason = attemptModel(HttpModelFetcher(limits, { false }), testEntry(url = unreachable), staging).failedReason("a fetcher with a past deadline")
        assertEquals("timed out", reason)
        noPartial()
    }

    @Test
    fun `the public constructor uses the cancel signal it is given`() {
        val reason = attemptModel(HttpModelFetcher(DownloadLimits(), { true }), testEntry(url = unreachable), staging).failedReason("a cancelled fetcher")
        assertEquals("cancelled", reason)
        noPartial()
    }
}
