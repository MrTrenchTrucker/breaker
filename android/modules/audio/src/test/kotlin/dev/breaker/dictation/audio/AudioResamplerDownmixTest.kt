package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bringing stereo down to the mono the pipeline is specified in.
 */
class AudioResamplerDownmixTest {

    // ── downmixing ──────────────────────────────────────────────────────

    @Test
    fun `stereo becomes mono by averaging`() {
        val stereo = floatArrayOf(1f, -1f, 0.5f, 0.5f, 0f, 0f)
        val mono = ChannelDownmixer(channelCount = 2).downmix(stereo)
        assertEquals(listOf(0f, 0.5f, 0f), mono.toList())
    }

    @Test
    fun `a mono signal passes through a mono downmixer unchanged`() {
        val mono = AudioSignals.speech(400)
        val out = ChannelDownmixer(channelCount = 1).downmix(mono)
        assertTrue(out.contentEquals(mono))
    }

    @Test
    fun `a trailing partial frame is dropped rather than divided by channels it lacks`() {
        // Three samples of stereo is one whole frame and half a frame; the half
        // would be averaged over channels that are not there.
        val stereo = floatArrayOf(1f, 0f, 1f, 0f, 1f)
        val mono = ChannelDownmixer(channelCount = 2).downmix(stereo)
        assertEquals(2, mono.size)
        assertEquals(0.5f, mono[0], 1e-6f)
    }

    @Test
    fun `a channel count of zero is refused`() {
        try {
            ChannelDownmixer(channelCount = 0)
            fail("expected a zero channel count to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("channelCount"))
        }
    }

    @Test
    fun `empty input downmixes to empty output`() {
        assertEquals(0, ChannelDownmixer(channelCount = 2).downmix(FloatArray(0)).size)
    }
}
