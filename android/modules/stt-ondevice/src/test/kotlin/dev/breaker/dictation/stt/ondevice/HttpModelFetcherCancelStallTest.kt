package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The per-call cancel of the four-argument [ModelFetcher.fetchModel] and the stall bound of
 * [HttpModelFetcher]: a body that brings no byte for [DownloadLimits.stallTimeoutMillis] ends as
 * Failed("stalled"), and a raised cancel ends as Failed("cancelled") with the staging cleaned.
 * Nothing here opens a socket or waits: the clock is the injected [FakeClock].
 */
class HttpModelFetcherCancelStallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    /** One [firstBytes] byte, then zero-byte reads that move [clock]: a read that brings nothing is a stall. */
    private class StallingStream(
        private val firstBytes: Int,
        private val clock: FakeClock,
        private val stallMillis: Long,
    ) : ProbeStream() {
        private var served = false

        override fun fill(b: ByteArray, off: Int, len: Int): Int {
            if (!served) {
                served = true
                val n = minOf(len, firstBytes)
                java.util.Arrays.fill(b, off, off + n, 7.toByte())
                return n
            }
            clock.advance(stallMillis)
            return 0
        }
    }

    private fun noPartial() = assertEquals("no file is left in staging", emptyList<String>(), stagedNames(staging))

    /** Calls the four-argument form and keeps its result or thrown exception, like [attemptModel]. */
    private fun attemptFour(
        fetcher: ModelFetcher,
        entry: ModelEntry = testEntry(),
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Attempt = try {
        Attempt(fetcher.fetchModel(entry, staging, onProgress, cancelled), null)
    } catch (e: Exception) {
        Attempt(null, e)
    }

    @Test
    fun `a body that brings no byte past the stall bound is Failed stalled and leaves no file`() {
        val clock = FakeClock()
        val stream = StallingStream(firstBytes = 1000, clock = clock, stallMillis = 101L)
        val opener = FakeOpener(serve(reply(body = stream)))
        val limits = DownloadLimits(stallTimeoutMillis = 100L)
        val reason = attemptFour(fetcherOf(opener, limits, clock = clock))
            .failedReason("a body that stops bringing bytes")
        assertEquals("stalled", reason)
        assertTrue("stopped on the empty read, reads=${stream.reads}", stream.reads == 2)
        assertTrue("the body was closed", stream.closes > 0)
        noPartial()
    }

    @Test
    fun `the default stall bound is two minutes`() {
        assertEquals("the default stall timeout in milliseconds", 120_000L, DownloadLimits().stallTimeoutMillis)
    }

    @Test
    fun `a slow but honest body that keeps bringing bytes is not called stalled`() {
        val clock = FakeClock()
        // Every read brings a chunk, so the last-progress mark moves and the bound never trips,
        // even though the whole download is far longer than the stall bound.
        val body = HookStream(total = 3_000L, chunk = 1_000) { clock.advance(60_000L) }
        val opener = FakeOpener(serve(reply(body = body, contentLength = 3_000L)))
        val limits = DownloadLimits(stallTimeoutMillis = 120_000L)
        val file = attemptFour(fetcherOf(opener, limits, clock = clock)).fetchedFile("a slow but honest body")
        assertEquals("the whole body was kept", 3_000L, file.length())
    }

    @Test
    fun `a cancel flag on the four-argument form stops the copy and cleans staging`() {
        val cancel = CancelFlag()
        val body = HookStream(total = 1_000_000, chunk = 1000) { index -> if (index == 3) cancel.on = true }
        val outputs = RecordingOutputs()
        val opener = FakeOpener(serve(reply(body = body)))
        val reason = attemptFour(fetcherOf(opener, openOutput = outputs::open), cancelled = cancel.check)
            .failedReason("cancel raised on read 3")
        assertEquals("cancelled", reason)
        assertTrue("stopped right after the cancel, reads=${body.reads}", body.reads in 3..4)
        assertEquals("control: a partial file had been started", 1, outputs.files.size)
        assertTrue("the output stream was closed", outputs.closes > 0)
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `the four-argument form keeps the constructor cancel signal beside its own flag`() {
        val opener = FakeOpener(emptyList<Step>())
        val reason = attemptFour(fetcherOf(opener, cancelled = { true }), cancelled = { false })
            .failedReason("the constructor's cancel")
        assertEquals("cancelled", reason)
        assertEquals("no request was made", 0, opener.requests.size)
        noPartial()
    }

    @Test
    fun `the four-argument form fetches like the three-argument form when neither cancels`() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val seen = ArrayList<Pair<Long, Long>>()
        val opener = FakeOpener(serve(okBytes(payload)))
        val file = attemptFour(fetcherOf(opener), onProgress = { received, total -> seen.add(received to total) })
            .fetchedFile("a four-argument fetch")
        assertArrayEquals("the staged bytes are the served bytes", payload, file.readBytes())
        assertTrue("one report carries the final length: $seen", seen.size == 1 && seen.single().second == payload.size.toLong())
    }
}
