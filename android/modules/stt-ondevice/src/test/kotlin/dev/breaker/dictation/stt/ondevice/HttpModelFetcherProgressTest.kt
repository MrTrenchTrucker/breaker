package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Progress reports of the real fetcher: stepped by bytes, one final report when the
 * body ends, and a total that is the length of the final reply.
 */
class HttpModelFetcherProgressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    /** One scripted 200 reply whose body arrives in reads of at most [chunk] bytes. */
    private fun servedInChunks(payload: ByteArray, chunk: Int, declared: Long = payload.size.toLong()): Step =
        serve(reply(200, BytesStream(payload, chunk), contentLength = declared))

    /** Fetches with the three-argument form; every report is recorded as a (received, total) pair. */
    private fun fetchReporting(
        opener: FakeOpener,
        limits: DownloadLimits,
        seen: MutableList<Pair<Long, Long>>,
        entry: ModelEntry = testEntry(),
    ): Attempt = try {
        val result = fetcherOf(opener, limits).fetchModel(entry, staging) { received, total ->
            seen.add(received to total)
        }
        Attempt(result, null)
    } catch (e: Exception) {
        Attempt(null, e)
    }

    private fun receivedOf(seen: List<Pair<Long, Long>>): List<Long> = seen.map { it.first }

    @Test
    fun `reports are stepped by bytes and the last one is the end`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val opener = FakeOpener(servedInChunks(ByteArray(1000) { 7.toByte() }, chunk = 100))
        fetchReporting(opener, DownloadLimits(progressStepBytes = 250L), seen).fetchedFile("stepped reports")
        assertEquals("the received values", listOf(300L, 600L, 900L, 1000L), receivedOf(seen))
        assertTrue("every total is 1000: $seen", seen.all { it.second == 1000L })
    }

    @Test
    fun `a body shorter than a step gets exactly one report at the end`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val opener = FakeOpener(servedInChunks(ByteArray(1000) { 7.toByte() }, chunk = 100))
        fetchReporting(opener, DownloadLimits(progressStepBytes = 5000L), seen).fetchedFile("short body")
        assertEquals("the only report is the end", listOf(1000L), receivedOf(seen))
    }

    @Test
    fun `a body that ends on a step boundary gets no second report`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val opener = FakeOpener(servedInChunks(ByteArray(1000) { 7.toByte() }, chunk = 100))
        fetchReporting(opener, DownloadLimits(progressStepBytes = 500L), seen).fetchedFile("boundary end")
        assertEquals("a report at 500 and at the end, no third", listOf(500L, 1000L), receivedOf(seen))
    }

    @Test
    fun `no report comes before the first step`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val opener = FakeOpener(servedInChunks(ByteArray(1000) { 7.toByte() }, chunk = 100))
        fetchReporting(opener, DownloadLimits(progressStepBytes = 400L), seen).fetchedFile("first step")
        val first = receivedOf(seen).firstOrNull() ?: -1L
        assertTrue("the first report is at least one step: $seen", first >= 400L)
        assertFalse("no report of zero bytes: $seen", receivedOf(seen).contains(0L))
    }

    @Test
    fun `a truncated body gets no final report`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val short = servedInChunks(ByteArray(600) { 7.toByte() }, chunk = 100, declared = 1000L)
        val attempt = fetchReporting(FakeOpener(short), DownloadLimits(progressStepBytes = 400L), seen)
        assertEquals("the body is cut short", "truncated", attempt.failedReason("600 bytes under a declared 1000"))
        assertEquals("the reports stop at the step, with no end report", listOf(400L), receivedOf(seen))
    }

    @Test
    fun `the total is the length of the final reply`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val opener = FakeOpener(
            serve(redirectTo(HttpFixtures.RELEASE_URL)),
            servedInChunks(ByteArray(300) { 7.toByte() }, chunk = 100),
        )
        fetchReporting(opener, DownloadLimits(progressStepBytes = 100L), seen).fetchedFile("redirect then body")
        assertTrue("reports arrived: $seen", seen.isNotEmpty())
        assertTrue("every total is the 300 of the final reply: $seen", seen.all { it.second == 300L })
    }

    @Test
    fun `an unknown length reports a total of minus one`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val opener = FakeOpener(serve(okBytes(ByteArray(1000) { 7.toByte() }, declare = false)))
        fetchReporting(opener, DownloadLimits(), seen).fetchedFile("unknown length")
        assertTrue("reports arrived: $seen", seen.isNotEmpty())
        assertTrue("every total is -1: $seen", seen.all { it.second == -1L })
        assertEquals("the last received is the payload size", 1000L, seen.last().first)
    }

    @Test
    fun `the two-argument form fetches without a callback`() {
        val payload = ByteArray(1000) { 7.toByte() }
        val file = attemptModel(fetcherOf(FakeOpener(serve(okBytes(payload)))), testEntry(), staging)
            .fetchedFile("two-argument form")
        assertEquals("the staged file has the payload's size", payload.size.toLong(), file.length())
    }

    @Test
    fun `the default step is four mebibytes`() {
        assertEquals("DownloadLimits default step", 4_194_304L, DownloadLimits().progressStepBytes)
    }
}
