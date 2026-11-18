package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The keyword stream under the real single-slot word stream, with a scripted spotter: what the downstream
 * matcher would receive for each hit. The real matcher is not on this module's test classpath (the build file
 * lists only the core and registry projects), so the words are checked here and the matcher is traced by hand
 * in the notes. Every wait is a signal; the class Timeout rule is only a safety net.
 */
class KeywordStreamRunTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    private val tag = "stt-ondevice keyword run:"

    private class Outcome(val stream: OnDeviceWordStream, val updates: List<SeenUpdate>, val recognizerReleases: Int) {
        val partials: List<SeenUpdate> get() = updates.filter { !it.final }
        val finals: List<SeenUpdate> get() = updates.filter { it.final }
    }

    /** Runs a word stream over [fake] with [chunks] fed blocks, waits for each to be decoded, stops and reports. */
    private fun runKeywords(fake: FakeSpotterStream, chunks: Int, samples: Int = 160): Outcome {
        var recognizerReleases = 0
        val recognizer = object : NativeStreamingRecognizer {
            override fun createStream(): NativeStream = HitNativeStream(fake)

            override fun release() {
                recognizerReleases++
            }
        }
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, _ -> recognizer },
            dispatcher = ProbeDispatcher(),
            flushDeadline = NEVER_EXPIRES,
        )
        val sink = UpdateSink(0)
        stream.start { sink.record(it) }
        repeat(chunks) { stream.feed(timedChunk(samples)) }
        runBlocking { repeat(chunks) { fake.decoded.receive() } }
        stream.stop()
        return Outcome(stream, sink.updates, recognizerReleases)
    }

    private val wakeWords = listOf(HeardWord("breaker", 500L), HeardWord("breaker", 1_000L))
    private val sendWords = listOf(HeardWord("and", 2_000L), HeardWord("I'm", 2_250L), HeardWord("gone", 2_500L))

    @Test
    fun `one wake hit gives one update with both words at their times and then the final`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, null))
        val outcome = runKeywords(fake, chunks = 3)
        assertEquals("$tag one update for one hit", listOf(wakeWords), outcome.partials.map { it.words })
        assertEquals("$tag the final carries the same words", listOf(wakeWords), outcome.finals.map { it.words })
        assertEquals("$tag the spotter is reset once for the one hit", 1, fake.count("reset"))
    }

    @Test
    fun `one send hit gives and then the merged contraction from its apostrophe pieces then gone`() {
        val fake = FakeSpotterStream(listOf(SEND_HIT, null))
        val outcome = runKeywords(fake, chunks = 2)
        assertEquals("$tag the apostrophe pieces merge into one word", listOf(sendWords), outcome.partials.map { it.words })
        assertEquals("$tag one reset for one hit", 1, fake.count("reset"))
    }

    @Test
    fun `the send spelling without an apostrophe merges to the word im`() {
        val noApostrophe = SpotterHit(
            keyword = "AND IM GONE",
            tokens = listOf("${SPOT_MARK}AND", "${SPOT_MARK}I", "M", "${SPOT_MARK}GO", "NE"),
            timestampsSeconds = listOf(2.0f, 2.25f, 2.375f, 2.5f, 2.5625f),
        )
        val outcome = runKeywords(FakeSpotterStream(listOf(noApostrophe)), chunks = 1)
        assertEquals(
            "$tag the pieces I and M join as one word",
            listOf(listOf(HeardWord("and", 2_000L), HeardWord("im", 2_250L), HeardWord("gone", 2_500L))),
            outcome.partials.map { it.words },
        )
    }

    @Test
    fun `a result the library repeats after reset gives no second update`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, null, null), AfterReset.KEEPS)
        val outcome = runKeywords(fake, chunks = 4)
        assertEquals("$tag a repeated result must not be delivered twice", 1, outcome.partials.size)
        assertEquals("$tag and the repeated result must not be reset again", 1, fake.count("reset"))
    }

    @Test
    fun `a result the library clears after reset gives no empty update and no second update`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, null, null), AfterReset.CLEARS)
        val outcome = runKeywords(fake, chunks = 4)
        assertEquals("$tag a cleared result must not send an empty update", listOf(wakeWords), outcome.partials.map { it.words })
        assertEquals("$tag one reset for one hit", 1, fake.count("reset"))
    }

    @Test
    fun `two hits give two updates in order`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, SEND_HIT, null), AfterReset.CLEARS)
        val outcome = runKeywords(fake, chunks = 4)
        assertEquals("$tag the wake update then the send update", listOf(wakeWords, sendWords), outcome.partials.map { it.words })
        assertEquals("$tag each hit is reset once", 2, fake.count("reset"))
    }

    @Test
    fun `an empty result gives no update and no reset`() {
        val fake = FakeSpotterStream(listOf(null, null))
        val outcome = runKeywords(fake, chunks = 2)
        assertEquals("$tag an empty result sends no update before the final", 0, outcome.partials.size)
        assertEquals("$tag the final is empty", listOf(emptyList<HeardWord>()), outcome.finals.map { it.words })
        assertEquals("$tag nothing was reset", 0, fake.count("reset"))
    }

    @Test
    fun `a hit without pieces gives its words from the keyword text at the fed position`() {
        val bare = SpotterHit("AND I'M GONE", emptyList(), emptyList())
        val outcome = runKeywords(FakeSpotterStream(listOf(bare)), chunks = 1, samples = 160)
        assertEquals(
            "$tag the keyword words all carry the position of the audio fed so far, 160 samples = 10 ms",
            listOf(listOf(HeardWord("and", 10L), HeardWord("I'm", 10L), HeardWord("gone", 10L))),
            outcome.partials.map { it.words },
        )
    }

    @Test
    fun `the spotter stream and the recognizer are released when the stream stops`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT))
        val outcome = runKeywords(fake, chunks = 1)
        assertTrue("$tag the spotter stream was released", fake.released.isCompleted)
        assertEquals("$tag the spotter stream was released once", 1, fake.count("release"))
        assertEquals("$tag the recognizer was released once", 1, outcome.recognizerReleases)
    }

    @Test
    fun `an engine failure ends the run quietly with no update and releases the stream`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null), failDecodeOn = 1)
        val outcome = runKeywords(fake, chunks = 1)
        assertEquals("$tag a failed decode sends no update and no final", 0, outcome.updates.size)
        assertEquals("$tag the failure is counted", 1L, outcome.stream.failures)
        assertEquals("$tag the spotter stream is released after the failure", 1, fake.count("release"))
        assertEquals("$tag a failed decode is never followed by a reset", 0, fake.count("reset"))
    }
}
