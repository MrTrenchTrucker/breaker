package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Time, cancel, free-space and content-type limits of the real fetcher. */
class HttpModelFetcherLimitsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    private val cap = HttpFixtures.CAP_1MB

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    private fun model(reply: HttpReply, limits: DownloadLimits = DownloadLimits(), outputs: RecordingOutputs = RecordingOutputs()): Attempt =
        attemptModel(fetcherOf(FakeOpener(serve(reply)), limits, openOutput = outputs::open), testEntry(), staging)

    private fun noPartial() = assertEquals("no file is left in staging", emptyList<String>(), stagedNames(staging))

    @Test
    fun `the total time limit stops a body that keeps arriving`() {
        val clock = FakeClock()
        val body = HookStream(total = 1_500_000, chunk = 1000) { clock.advance(150) }
        val outputs = RecordingOutputs()
        val opener = FakeOpener(serve(reply(body = body)))
        val limits = DownloadLimits(totalTimeoutMillis = 1000L)
        val reason = attemptModel(fetcherOf(opener, limits, clock = clock, openOutput = outputs::open), testEntry(), staging)
            .failedReason("clock passes the limit at read 7")
        assertEquals("timed out", reason)
        assertTrue("stopped right after the limit passed, reads=${body.reads}", body.reads in 7..8)
        assertTrue("the body was closed", body.closes > 0)
        assertFalse("the partial file is gone", outputs.files.any { it.exists() })
        noPartial()
    }

    @Test
    fun `the total time limit is measured from the start of the fetch`() {
        val clock = FakeClock(start = 9_000_000_000L)
        val opener = FakeOpener(serve(okBytes(ByteArray(2000) { 1 })))
        val limits = DownloadLimits(totalTimeoutMillis = 1000L)
        attemptModel(fetcherOf(opener, limits, clock = clock), testEntry(), staging).fetchedFile("a clock far from zero")
    }

    @Test
    fun `the total time limit is checked before each redirect hop`() {
        val clock = FakeClock()
        val opener = FakeOpener(
            listOf(serve(redirectTo(HttpFixtures.RELEASE_URL)), serve(okBytes(ByteArray(10) { 1 }))),
            onRequest = { index -> if (index == 0) clock.advance(5000) },
        )
        val limits = DownloadLimits(totalTimeoutMillis = 1000L)
        val reason = attemptModel(fetcherOf(opener, limits, clock = clock), testEntry(), staging).failedReason("clock passes the limit during hop 0")
        assertEquals("timed out", reason)
        assertEquals("the second hop was never requested", 1, opener.requests.size)
    }

    @Test
    fun `a cancel raised during the copy stops it and deletes the partial file`() {
        val cancel = CancelFlag()
        val body = HookStream(total = 1_000_000, chunk = 1000) { index -> if (index == 3) cancel.on = true }
        val outputs = RecordingOutputs()
        val opener = FakeOpener(serve(reply(body = body)))
        val reason = attemptModel(fetcherOf(opener, cancelled = cancel.check, openOutput = outputs::open), testEntry(), staging)
            .failedReason("cancel raised on read 3")
        assertEquals("cancelled", reason)
        assertTrue("stopped right after the cancel, reads=${body.reads}", body.reads in 3..4)
        assertEquals("control: a partial file had been started", 1, outputs.files.size)
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `a cancel raised before the first request makes no request`() {
        val cancel = CancelFlag()
        cancel.on = true
        val opener = FakeOpener(emptyList<Step>())
        val model = attemptModel(fetcherOf(opener, cancelled = cancel.check), testEntry(), staging)
        assertEquals("cancelled", model.failedReason("cancel before a model fetch"))
        val list = attemptChecksums(fetcherOf(opener, cancelled = cancel.check), staging)
        assertEquals("cancelled", list.failedReason("cancel before a checksum fetch"))
        assertEquals("no request was made", 0, opener.requests.size)
        noPartial()
    }

    @Test
    fun `a cancel raised while the first hop is open makes no second request`() {
        val cancel = CancelFlag()
        val opener = FakeOpener(
            listOf(serve(redirectTo(HttpFixtures.RELEASE_URL)), serve(okBytes(ByteArray(10) { 1 }))),
            onRequest = { index -> if (index == 0) cancel.on = true },
        )
        val reason = attemptModel(fetcherOf(opener, cancelled = cancel.check), testEntry(), staging).failedReason("cancel during hop 0")
        assertEquals("cancelled", reason)
        assertEquals("only the first hop was requested", 1, opener.requests.size)
    }

    @Test
    fun `the configured connect and read timeouts reach the opener on every hop`() {
        val limits = DownloadLimits(connectTimeoutMillis = 1234, readTimeoutMillis = 5678)
        val opener = FakeOpener(serve(redirectTo(HttpFixtures.RELEASE_URL)), serve(okBytes(ByteArray(10) { 1 })))
        attemptModel(fetcherOf(opener, limits), testEntry(), staging).fetchedFile("two hops")
        assertEquals(2, opener.requests.size)
        for (request in opener.requests) {
            assertEquals("connect timeout", 1234, request.connectTimeoutMillis)
            assertEquals("read timeout", 5678, request.readTimeoutMillis)
        }
        val listOpener = FakeOpener(serve(okBytes(ByteArray(10) { 1 })))
        attemptChecksums(fetcherOf(listOpener, limits), staging).fetchedFile("checksum fetch")
        assertEquals(1234, listOpener.requests[0].connectTimeoutMillis)
        assertEquals(5678, listOpener.requests[0].readTimeoutMillis)
    }

    @Test
    fun `the default timeouts are fifteen and thirty seconds`() {
        val opener = FakeOpener(serve(okBytes(ByteArray(10) { 1 })))
        attemptModel(fetcherOf(opener), testEntry(), staging).fetchedFile("default limits")
        assertEquals(15_000, opener.requests[0].connectTimeoutMillis)
        assertEquals(30_000, opener.requests[0].readTimeoutMillis)
    }

    @Test
    fun `a 200 with a JSON content type is refused`() {
        for (type in listOf("application/json", "Application/JSON; charset=utf-8", "application/json;charset=UTF-8")) {
            val reason = model(reply(body = SizedStream(50), contentType = type)).failedReason("content type $type")
            assertEquals("unexpected content type", reason)
            noPartial()
        }
    }

    @Test
    fun `a 200 with a binary or missing content type is accepted`() {
        for (type in listOf("application/octet-stream", "binary/octet-stream", "application/x-bzip2", null)) {
            model(reply(body = SizedStream(50), contentType = type)).fetchedFile("content type $type")
        }
    }

    @Test
    fun `free space below the need throws an IOException and leaves no file`() {
        val body = SizedStream(1000)
        val opener = FakeOpener(serve(reply(body = body, contentLength = 1000)))
        val thrown = attemptModel(fetcherOf(opener, usableSpace = { 999L }), testEntry(), staging).thrownIo("space 999 for 1000 bytes")
        assertTrue("the failure names the space", (thrown.message ?: "").contains("not enough space"))
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `free space equal to the declared length is enough`() {
        val opener = FakeOpener(serve(reply(body = SizedStream(1000), contentLength = 1000)))
        attemptModel(fetcherOf(opener, usableSpace = { 1000L }), testEntry(), staging).fetchedFile("space 1000 for 1000 bytes")
    }

    @Test
    fun `without a declared length the need is the cap`() {
        val low = FakeOpener(serve(reply(body = SizedStream(10))))
        val thrown = attemptModel(fetcherOf(low, usableSpace = { cap - 1 }), testEntry(), staging).thrownIo("space cap minus one")
        assertTrue("the failure names the space", (thrown.message ?: "").contains("not enough space"))
        noPartial()
        val enough = FakeOpener(serve(reply(body = SizedStream(10))))
        attemptModel(fetcherOf(enough, usableSpace = { cap }), testEntry(), staging).fetchedFile("space equal to the cap")
    }

    @Test
    fun `free space is asked about the staging directory`() {
        val asked = ArrayList<File>()
        val opener = FakeOpener(serve(okBytes(ByteArray(10) { 1 })))
        attemptModel(fetcherOf(opener, usableSpace = { dir -> asked.add(dir); Long.MAX_VALUE }), testEntry(), staging).fetchedFile("space query")
        assertTrue("the directory asked about is the staging directory: $asked", asked.isNotEmpty() && asked.all { it.canonicalFile == staging.canonicalFile })
    }
}
