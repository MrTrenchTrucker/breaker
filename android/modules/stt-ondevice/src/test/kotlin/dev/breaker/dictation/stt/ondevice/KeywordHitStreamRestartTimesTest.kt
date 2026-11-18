package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents what the hit stream delivers when the spotter's hit times restart after a reset. Whether the real
 * library does that is UNVERIFIED (read from strings only, never run on a device), so this test records the
 * current pass-through behaviour and does not claim it is right: a second hit then carries a start time that is
 * too small for a send offset. No fix is built.
 */
class KeywordHitStreamRestartTimesTest {

    private val tag = "stt-ondevice keyword restart:"

    /**
     * A scripted spotter whose reported times are relative to the last reset: the script holds times from the
     * start of the whole audio, and the hit it reports has the audio fed up to the last reset taken off them.
     * Every other call goes to [inner], which clears its keyword on a reset.
     */
    private class RestartingSpotter(val inner: FakeSpotterStream) : SpotterStream by inner {
        private var fedSamples = 0L
        private var originSeconds = 0.0f

        override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
            inner.acceptWaveform(samples, sampleRateHz)
            fedSamples += samples.size
        }

        override fun hit(): SpotterHit? = inner.hit()?.let { found ->
            found.copy(timestampsSeconds = found.timestampsSeconds.map { it - originSeconds })
        }

        override fun reset() {
            inner.reset()
            originSeconds = fedSamples / 16_000.0f
        }
    }

    /** Feeds one second of audio (exact in binary at 16 kHz) and decodes, as the word stream does for a chunk. */
    private fun oneSecond(stream: HitNativeStream) {
        stream.acceptWaveform(FloatArray(16_000), 16_000)
        while (stream.isReady()) stream.decode()
    }

    @Test
    fun `a second hit whose times restart after a reset is passed through with the restarted times`() {
        val inner = FakeSpotterStream(listOf(WAKE_HIT, null, SEND_HIT), AfterReset.CLEARS)
        val stream = HitNativeStream(RestartingSpotter(inner))

        oneSecond(stream)
        val wake = stream.result()
        assertEquals("$tag the first hit has no reset before it, so its times are as scripted", WAKE_HIT.timestampsSeconds, wake.timestampsSeconds)

        oneSecond(stream)
        oneSecond(stream)
        val send = stream.result()
        assertEquals("$tag the second hit is the send keyword", SEND_HIT.keyword, send.text)
        assertEquals("$tag the second hit keeps its pieces", SEND_HIT.tokens, send.tokens)
        // The send phrase starts at 2.0 s of the audio, but the restarted times are 1.0 s less: passed through as given.
        assertEquals(
            "$tag the second hit carries the restarted times, one second too small",
            listOf(1.0f, 1.25f, 1.3125f, 1.375f, 1.5f, 1.5625f),
            send.timestampsSeconds,
        )
        assertEquals("$tag each of the two hits is reset once", 2, inner.count("reset"))
    }
}
