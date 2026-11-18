package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The hit logic over a scripted spotter stream: one result per hit, safe whether the library keeps or clears its
 * keyword after a reset. Nothing here loads the native library and nothing runs on a thread of its own.
 */
class KeywordHitStreamTest {

    private val tag = "stt-ondevice keyword stream:"

    /** Feeds one block of [samples] and decodes once, as the word stream does for a chunk. */
    private fun step(stream: HitNativeStream, samples: Int = 160) {
        stream.acceptWaveform(FloatArray(samples), 16_000)
        while (stream.isReady()) stream.decode()
    }

    private fun steps(stream: HitNativeStream, count: Int) {
        repeat(count) { step(stream) }
    }

    @Test
    fun `before any hit the result is empty with no pieces and no times`() {
        val stream = HitNativeStream(FakeSpotterStream(listOf(null)))
        step(stream)
        val result = stream.result()
        assertEquals("$tag no hit means empty text", "", result.text)
        assertEquals("$tag no hit means no pieces", emptyList<String>(), result.tokens)
        assertEquals("$tag no hit means no times", emptyList<Float>(), result.timestampsSeconds)
        assertEquals("$tag text() agrees with the result", "", stream.text())
    }

    @Test
    fun `a hit is returned with its keyword pieces and times`() {
        val stream = HitNativeStream(FakeSpotterStream(listOf(WAKE_HIT)))
        step(stream)
        val result = stream.result()
        assertEquals("$tag the keyword is the text", "BREAKER BREAKER", result.text)
        assertEquals("$tag the pieces are the hit pieces", WAKE_HIT.tokens, result.tokens)
        assertEquals("$tag the times are the hit times", WAKE_HIT.timestampsSeconds, result.timestampsSeconds)
        assertEquals("$tag text() is the keyword", "BREAKER BREAKER", stream.text())
    }

    @Test
    fun `a library that clears its keyword on reset gives one hit and one reset`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, null), AfterReset.CLEARS)
        val stream = HitNativeStream(fake)
        step(stream)
        val first = stream.result()
        steps(stream, 2)
        assertEquals("$tag one hit is reset once", 1, fake.count("reset"))
        assertSame("$tag the kept hit is not replaced by later empty decodes", first, stream.result())
    }

    @Test
    fun `a library that keeps its keyword on reset still gives one hit and one reset`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, null, null), AfterReset.KEEPS)
        val stream = HitNativeStream(fake)
        step(stream)
        val first = stream.result()
        steps(stream, 3)
        assertEquals("$tag a repeated result after reset must not be reset again", 1, fake.count("reset"))
        assertSame("$tag a repeated result after reset must not replace the hit", first, stream.result())
    }

    @Test
    fun `a different hit after a kept one is a new hit with its own reset`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, SEND_HIT), AfterReset.KEEPS)
        val stream = HitNativeStream(fake)
        steps(stream, 3)
        assertEquals("$tag two different hits are reset twice", 2, fake.count("reset"))
        assertEquals("$tag the latest hit is the send hit", SEND_HIT.keyword, stream.result().text)
        assertEquals("$tag the latest times are the send times", SEND_HIT.timestampsSeconds, stream.result().timestampsSeconds)
    }

    @Test
    fun `an identical hit after a cleared reset is a new hit`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, WAKE_HIT), AfterReset.CLEARS)
        val stream = HitNativeStream(fake)
        steps(stream, 3)
        assertEquals("$tag a hit the library reports again after clearing is a hit again", 2, fake.count("reset"))
    }

    @Test
    fun `two hits give two results with different times`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT, null, SEND_HIT), AfterReset.CLEARS)
        val stream = HitNativeStream(fake)
        step(stream)
        val wake = stream.result()
        steps(stream, 2)
        val send = stream.result()
        assertNotEquals("$tag the two hits must differ in their first time", wake.timestampsSeconds.first(), send.timestampsSeconds.first())
        assertEquals("$tag the first result kept the wake keyword", "BREAKER BREAKER", wake.text)
        assertEquals("$tag the second result is the send keyword", "AND I'M GONE", send.text)
        assertEquals("$tag two hits are reset twice", 2, fake.count("reset"))
    }

    @Test
    fun `an empty or blank keyword is no hit and nothing is reset`() {
        val blank = SpotterHit("   ", listOf("${SPOT_MARK}X"), listOf(0.5f))
        val empty = SpotterHit("", emptyList(), emptyList())
        val fake = FakeSpotterStream(listOf(empty, blank), AfterReset.KEEPS)
        val stream = HitNativeStream(fake)
        steps(stream, 2)
        assertEquals("$tag an empty or blank keyword must not be reset", 0, fake.count("reset"))
        assertEquals("$tag an empty or blank keyword gives no text", "", stream.result().text)
        assertEquals("$tag an empty or blank keyword gives no pieces", emptyList<String>(), stream.result().tokens)
    }

    @Test
    fun `a hit without pieces is built from the keyword words at the audio position fed so far`() {
        val bare = SpotterHit("BREAKER BREAKER", emptyList(), emptyList())
        val stream = HitNativeStream(FakeSpotterStream(listOf(bare)))
        step(stream, samples = 1_600)
        val result = stream.result()
        assertEquals("$tag one piece per keyword word, each opening a word", listOf("${SPOT_MARK}BREAKER", "${SPOT_MARK}BREAKER"), result.tokens)
        assertEquals("$tag every piece is at the fed position, 1600 samples at 16 kHz", listOf(0.1f, 0.1f), result.timestampsSeconds)
        assertEquals("$tag the text is the keyword", "BREAKER BREAKER", result.text)
    }

    @Test
    fun `the fed position grows with the audio so a second bare hit has a later time`() {
        val bare = SpotterHit("AND I'M GONE", emptyList(), emptyList())
        val later = SpotterHit("BREAKER BREAKER", emptyList(), emptyList())
        val stream = HitNativeStream(FakeSpotterStream(listOf(bare, later), AfterReset.CLEARS))
        step(stream, samples = 1_600)
        val first = stream.result().timestampsSeconds
        step(stream, samples = 1_600)
        val second = stream.result().timestampsSeconds
        assertEquals("$tag first bare hit at 0.1 s", listOf(0.1f, 0.1f, 0.1f), first)
        assertEquals("$tag second bare hit at 0.2 s", listOf(0.2f, 0.2f), second)
    }

    @Test
    fun `a bare hit uses the first time the library gave when it gave one`() {
        val bare = SpotterHit("BREAKER BREAKER", emptyList(), listOf(0.5f))
        val stream = HitNativeStream(FakeSpotterStream(listOf(bare)))
        step(stream, samples = 1_600)
        assertEquals("$tag the library time wins over the fed position", listOf(0.5f, 0.5f), stream.result().timestampsSeconds)
    }

    @Test
    fun `pieces with fewer times than pieces repeat the last time`() {
        val short = SpotterHit("AND GONE", listOf("${SPOT_MARK}AND", "${SPOT_MARK}GO", "NE"), listOf(2.0f, 2.5f))
        val stream = HitNativeStream(FakeSpotterStream(listOf(short)))
        step(stream)
        assertEquals("$tag every piece gets a time", listOf(2.0f, 2.5f, 2.5f), stream.result().timestampsSeconds)
        assertEquals("$tag the pieces are unchanged", short.tokens, stream.result().tokens)
    }

    @Test
    fun `every call is passed on to the spotter stream`() {
        val fake = FakeSpotterStream(listOf(null))
        val stream = HitNativeStream(fake)
        stream.acceptWaveform(FloatArray(320), 16_000)
        assertTrue("$tag the spotter is ready after audio", stream.isReady())
        stream.decode()
        stream.inputFinished()
        stream.release()
        assertEquals("$tag one block was passed on", listOf(320), fake.sizes)
        assertEquals("$tag one decode", 1, fake.count("decode"))
        assertEquals("$tag input finished once", 1, fake.count("inputFinished"))
        assertEquals("$tag released once", 1, fake.count("release"))
    }

    @Test
    fun `an engine failure in decode passes out and nothing is reset`() {
        val fake = FakeSpotterStream(listOf(WAKE_HIT), failDecodeOn = 1)
        val stream = HitNativeStream(fake)
        stream.acceptWaveform(FloatArray(160), 16_000)
        try {
            stream.decode()
            fail("$tag the decode failure must reach the caller")
        } catch (expected: IllegalStateException) {
            assertEquals("$tag a failed decode must not reset", 0, fake.count("reset"))
        }
    }
}
