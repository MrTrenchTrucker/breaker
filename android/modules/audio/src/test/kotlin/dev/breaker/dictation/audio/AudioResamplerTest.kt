package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bringing captured audio to 16 kHz mono.
 *
 * The card's gotcha — resample at the boundary, not downstream — is why these
 * exist at all: the alternative is every consumer having to know what rate it
 * was handed. Draining the end of a take, and the stereo-to-mono conversion,
 * are held in their own files.
 */
class AudioResamplerTest {

    /**
     * The block size the capture thread reads from the device and resamples in
     * one piece: a 20 ms frame at 16 kHz, widened for the capture rate.
     */
    private val captureBlockSamples = 1_280

    /**
     * Output samples covering half a kernel width at 48 kHz -> 16 kHz, which is
     * as far ahead of the read point as the window reaches — and so the most a
     * take can lose at its end, where the samples the window would need never
     * arrive.
     */
    private val endOfTakeLoss = 6

    @Test
    fun `48 kHz audio becomes 16 kHz`() {
        val input = AudioSignals.tone(48_000, hz = 440.0, amplitude = 0.5f)
        val out = AudioResampler(fromSampleRateHz = 48_000).resample(input)

        assertTrue(
            "48 k samples should become about 16 k, got ${out.size}",
            out.size in 15_500..16_500,
        )
    }

    @Test
    fun `a signal that is already at 16 kHz comes out the same length`() {
        val input = AudioSignals.speech(1_600)
        val out = AudioResampler(fromSampleRateHz = 16_000).resample(input)
        assertTrue(
            "a 1:1 resample should keep the length, got ${out.size} from ${input.size}",
            out.size in 1_500..1_600,
        )
    }

    @Test
    fun `the tone survives the resample at its own frequency`() {
        // A resampler that folds the signal back on itself would still return
        // audio of roughly the right length; the pitch is what gives it away.
        val hz = 440.0
        val input = AudioSignals.tone(48_000, hz = hz, amplitude = 0.5f, sampleRateHz = 48_000.0)
        val out = AudioResampler(fromSampleRateHz = 48_000).resample(input)

        val measured = measureFrequency(out, AudioFormat.SAMPLE_RATE_HZ)
        assertEquals(
            "a 440 Hz tone came out at $measured Hz after 48k -> 16k",
            hz,
            measured,
            20.0,
        )
    }

    @Test
    fun `the level survives the resample`() {
        val input = AudioSignals.tone(48_000, hz = 440.0, amplitude = 0.5f, sampleRateHz = 48_000.0)
        val out = AudioResampler(fromSampleRateHz = 48_000).resample(input)
        // Skip the warm-up, where the kernel has no history behind it.
        val steady = out.copyOfRange(out.size / 4, out.size / 2)
        assertEquals(
            "the resample changed the level from ${AudioSignals.rms(input)} to " +
                "${AudioSignals.rms(steady)}",
            AudioSignals.rms(input),
            AudioSignals.rms(steady),
            0.05f,
        )
    }

    @Test
    fun `resampling in pieces gives the same samples as resampling at once`() {
        // The property that keeps a 20 ms frame boundary from being a click: the
        // filter history and the fractional position both survive the call.
        val whole = AudioSignals.speech(8_000)
        val inOneGo = AudioResampler(fromSampleRateHz = 16_000).resample(whole)

        val piecewise = AudioResampler(fromSampleRateHz = 16_000)
        val pieces = ArrayList<Float>()
        var offset = 0
        while (offset < whole.size) {
            val size = minOf(320, whole.size - offset)
            val frame = piecewise.resample(whole.copyOfRange(offset, offset + size))
            frame.forEach { pieces.add(it) }
            offset += size
        }

        val joined = FloatArray(pieces.size) { pieces[it] }
        assertEquals(
            "resampling 8 k samples in 320-sample frames gave ${joined.size} samples, " +
                "but in one go it gave ${inOneGo.size} — a piece is being dropped or " +
                "duplicated at the frame boundary",
            inOneGo.size,
            joined.size,
        )
        var worst = 0f
        for (i in inOneGo.indices) {
            worst = maxOf(worst, kotlin.math.abs(inOneGo[i] - joined[i]))
        }
        assertTrue(
            "resampling in 320-sample frames differs from resampling at once by up " +
                "to $worst, so the filter state is not carried across calls",
            worst < 0.05f,
        )

        // The same property at 48 kHz -> 16 kHz, which is the case that matters
        // and the one the 16 kHz case above cannot reach. At a 1:1 rate the read
        // step is exactly one sample, the read point always lands on a sample,
        // and the window's back half — the history taps, the two heaviest in the
        // kernel — is the one nobody needs to look behind for. At 48 kHz the step
        // is three samples, so every output sample sits a third of a sample
        // between two of them and the kernel has to reach back across the call
        // boundary for the samples behind it. Fed in the blocks the capture
        // thread actually reads, that must come back the same as resampling in
        // one go; a kernel that skips its own history instead drops the two taps
        // that dominate a windowed sinc while still dividing by their weight, and
        // puts a step at the end of every block.
        //
        // Nothing is excluded here. Every sample either side emits is compared,
        // including the last samples of every block: a windowed sinc reaches
        // half its width forward as well as back, so the tail of a block needs
        // samples that only the NEXT block can supply. A resampler that emits
        // that tail on the strength of taps it does not have — while still
        // dividing by their weight — puts a step at the end of every block,
        // which is the click this whole test exists to rule out.
        //
        // The two streams are compared over their common prefix rather than
        // index-for-index to the end. A resampler that waits for the samples
        // ahead of the read point emits its last half-kernel worth of output
        // only at the end of the take, so the piece-by-piece stream trails the
        // whole-signal one by a handful of samples; the shorter of the two is
        // the honest extent of the claim, and it is checked below to be
        // essentially all of the audio so it cannot quietly shrink.
        val wide = AudioSignals.tone(
            samples = 48_000,
            hz = 440.0,
            amplitude = 0.5f,
            sampleRateHz = 48_000.0,
        )
        val wideInOneGo = AudioResampler(fromSampleRateHz = 48_000).resample(wide)

        val widePiecewise = AudioResampler(fromSampleRateHz = 48_000)
        val widePieces = ArrayList<Float>()
        var wideOffset = 0
        var blockIndex = 0
        while (wideOffset < wide.size) {
            val size = minOf(captureBlockSamples, wide.size - wideOffset)
            val frame = widePiecewise.resample(wide.copyOfRange(wideOffset, wideOffset + size))
            frame.forEach { widePieces.add(it) }
            wideOffset += size
            blockIndex++
        }
        val wideJoined = FloatArray(widePieces.size) { widePieces[it] }

        val common = minOf(wideInOneGo.size, wideJoined.size)
        var wideWorst = 0f
        for (i in 0 until common) {
            wideWorst = maxOf(wideWorst, kotlin.math.abs(wideInOneGo[i] - wideJoined[i]))
        }

        assertTrue(
            "the $blockIndex blocks emitted ${wideJoined.size} samples in total, " +
                "against ${wideInOneGo.size} for the same audio in one go; a " +
                "difference of more than $endOfTakeLoss samples means whole blocks " +
                "are being dropped or duplicated at the boundary",
            kotlin.math.abs(wideInOneGo.size - wideJoined.size) <= endOfTakeLoss,
        )
        assertTrue(
            "only $common of ${wideInOneGo.size} samples could be compared, " +
                "so most of the audio went unchecked",
            common >= wideInOneGo.size - endOfTakeLoss,
        )
        assertTrue(
            "resampling in $captureBlockSamples-sample blocks differs from " +
                "resampling at once by up to $wideWorst over $common samples, " +
                "so the kernel is not carrying its history across the block boundary",
            wideWorst < 0.01f,
        )
    }


    @Test
    fun `resetting makes the next capture start clean`() {
        val resampler = AudioResampler(fromSampleRateHz = 16_000)
        val first = resampler.resample(AudioSignals.speech(1_600))
        resampler.reset()
        val second = resampler.resample(AudioSignals.speech(1_600))

        assertTrue(
            "after a reset the same input should give the same output; the " +
                "difference was ${maxDifference(first, second)}",
            maxDifference(first, second) < 0.01f,
        )
    }

    @Test
    fun `empty input gives empty output`() {
        assertEquals(0, AudioResampler(fromSampleRateHz = 44_100).resample(FloatArray(0)).size)
    }

    @Test
    fun `the output is 16 kHz mono float in range`() {
        val out = AudioResampler(fromSampleRateHz = 44_100)
            .resample(AudioSignals.speech(44_100))
        out.forEach { sample ->
            assertTrue("a sample was $sample, outside -1..1", sample >= -1f && sample <= 1f)
        }
    }

    @Test
    fun `a non-positive source rate is refused`() {
        try {
            AudioResampler(fromSampleRateHz = 0)
            fail("expected a zero source rate to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fromSampleRateHz"))
        }
    }

    @Test
    fun `an odd tap count is refused`() {
        try {
            AudioResampler(fromSampleRateHz = 48_000, taps = 15)
            fail("expected an odd tap count to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("even"))
        }
    }

    @Test
    fun `too few taps for a windowed sinc are refused`() {
        try {
            AudioResampler(fromSampleRateHz = 48_000, taps = 2)
            fail("expected two taps to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("taps"))
        }
    }

    private fun maxDifference(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var worst = 0f
        for (i in 0 until n) worst = maxOf(worst, kotlin.math.abs(a[i] - b[i]))
        return worst
    }

    /** Zero crossings per second, which is a frequency for anything tonal. */
    private fun measureFrequency(samples: FloatArray, sampleRateHz: Int): Double {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++
        }
        return crossings.toDouble() * sampleRateHz / (2.0 * samples.size)
    }
}
