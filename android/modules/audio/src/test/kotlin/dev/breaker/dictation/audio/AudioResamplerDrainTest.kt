package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draining the end of a take.
 *
 * The count a drain keeps, and the samples it reads to do it. What a drained
 * sample's VALUE should be, and the rate conversion itself, are pinned
 * elsewhere; this is the tail.
 */
class AudioResamplerDrainTest {

    @Test
    fun `the drained tail of a constant signal is still that constant`() {
        // The one thing the drain must not do is lose the samples behind the
        // read point. A read point is interpolated from a window that reaches
        // half a kernel BEHIND it as well as half a kernel ahead, and at the end
        // of a take the samples behind the first drained read point are the
        // history the last resample() call kept — not the pending tail, which
        // starts AT that read point. A drain that forgets to lay the history
        // down under the pending samples reads those back-half taps as silence,
        // so the first drained samples are computed over half a window of
        // zeros and sag toward zero.
        //
        // A constant is the reference that catches it, because a constant is
        // its own answer: a windowed sinc whose every tap is the same value
        // returns that value, whatever the read point and whatever fraction of
        // the window is real audio. Nothing about the resampler's internals is
        // consulted, and nothing is compared with the resampler itself — a
        // comparison between two of its own outputs would be satisfied by a
        // drain that is consistently wrong.
        //
        // This is also why the obvious test for the drain does not catch it.
        // "The same audio in pieces, then a drain" and "all at once, then a
        // drain" both end in the SAME drain with the SAME history — the input's
        // last taps either way — so a drain that drops the history corrupts
        // both tails identically and the two still agree. The properties that
        // do call drain() compare counts and self-comparisons; none of them
        // has an independent idea of what a drained sample's value should be.
        //
        // 48 kHz, not a rate of one, because at a rate of one every tap of the
        // window lands on a sinc null and the drained sample is the input
        // sample at the read point whatever the history holds — there is
        // nothing for a missing history to be missing from. At 48 kHz the step
        // is three input samples, so the window genuinely spans neighbours and
        // the history is load-bearing.
        val dc = 0.5f
        val from = 48_000
        val resampler = AudioResampler(fromSampleRateHz = from)
        val body = resampler.resample(FloatArray(takeSamples) { dc })
        val tail = resampler.drain()

        assertTrue(
            "the drain emitted ${tail.size} samples, so there is no tail to hold " +
                "a level",
            tail.size >= 4,
        )
        // The first samples are the ones the history sits behind: the later
        // ones are windowed over a signal that is zero past the end of the take,
        // which is the documented behaviour of a drain and is not what this
        // test is about. The first three are inside real audio.
        for (i in 0 until 3) {
            assertEquals(
                "drained sample $i is ${tail[i]} where the take is a constant " +
                    "$dc. The window reaches ${AudioResampler.DEFAULT_TAPS / 2} " +
                    "samples behind the read point, and the samples behind the " +
                    "first ones are the history the last resample() kept, not the " +
                    "pending tail. A drain that lays the pending samples down " +
                    "without the history under them reads that half-window as " +
                    "silence and the level sags toward zero",
                dc,
                tail[i],
                dcLevelTolerance,
            )
        }
        // And the count across both halves, so a drain that held the level by
        // emitting fewer read points would not pass: the total is the contract
        // the other drain tests pin, and holding a level is not a licence to
        // drop a sample.
        assertEquals(
            "a take of $takeSamples samples at $from Hz is " +
                "${ceilDiv(takeSamples * AudioFormat.SAMPLE_RATE_HZ, from)} samples " +
                "at ${AudioFormat.SAMPLE_RATE_HZ} Hz across resample and drain " +
                "together, and this one came to ${body.size + tail.size}",
            ceilDiv(takeSamples * AudioFormat.SAMPLE_RATE_HZ, from),
            body.size + tail.size,
        )
    }

    // ── draining the end of a take ─────────────────────────────────────

    @Test
    fun `the drained tail at a rate of one is the input tail sample for sample`() {
        // At 1:1 the window is centred on a sample and every other tap lands an
        // exact multiple of the step away, where the sinc has its nulls, so the
        // drained read points return the take's own last samples rather than an
        // interpolation of them. A drain that smoothed them, or reached a tap it
        // should not, would show up here as an exact difference.
        //
        // Compared over the DRAINED TAIL, not the whole take. The body is not
        // exact either way — a sinc's null is a null only up to the precision it
        // is evaluated at — and asserting on it would make this a test of
        // floating point rather than of the drain. What the drain adds is the
        // claim under test.
        val input = AudioSignals.speech(takeSamples)
        val resampler = AudioResampler(fromSampleRateHz = 16_000)
        val body = resampler.resample(input)
        val tail = resampler.drain()

        assertEquals(
            "a take of $takeSamples input samples at a rate of one is $takeSamples " +
                "output samples; resample gave ${body.size} and the drain ${tail.size}",
            takeSamples,
            body.size + tail.size,
        )
        assertEquals(
            "half a kernel of 32 taps is 16 samples, and the read points whose " +
                "forward window runs past the end of the take are the last 15 of " +
                "them, so the drain should have emitted 15 and emitted ${tail.size}",
            15,
            tail.size,
        )
        for (i in tail.indices) {
            val inputIndex = body.size + i
            assertEquals(
                "the drained sample at index $inputIndex is ${tail[i]} where the " +
                    "take has ${input[inputIndex]}. At a rate of one the window is " +
                    "centred on the sample and every other tap sits on a sinc null, " +
                    "so the drained tail is the input tail exactly",
                input[inputIndex],
                tail[i],
                0f,
            )
        }
    }

    @Test
    fun `48 kHz to 16 kHz comes out at an exact count whatever the take length`() {
        // The count is a contract, not an approximation: N samples in are
        // ceil(N / 3) samples out. Every take length here is one 3 does not
        // divide — the case where a resampler that placed its final read point
        // on a rounded-off position would come out one sample long or one
        // short.
        for (inputSize in intArrayOf(1_598, 1_599, 4_801, 4_802, 9_999)) {
            val resampler = AudioResampler(fromSampleRateHz = 48_000)
            val body = resampler.resample(AudioSignals.tone(inputSize, hz = 440.0, amplitude = 0.5f, sampleRateHz = 48_000.0))
            val tail = resampler.drain()
            val expected = ceilDiv(inputSize, 3)

            assertEquals(
                "a take of $inputSize samples at 48 kHz is $expected samples at " +
                    "16 kHz, and this one came to ${body.size + tail.size}",
                expected,
                body.size + tail.size,
            )
            assertTrue(
                "a take of $inputSize samples drained ${tail.size}; the window is " +
                    "half a kernel, so about 5 samples at this ratio",
                tail.size in 4..5,
            )
        }
    }

    @Test
    fun `draining twice emits the tail once`() {
        // The drain resets, so the second call has no read points left and the
        // take does not get its tail twice. A drain that did not reset would
        // hand the listener a second copy of the take's last fraction of a
        // millisecond — audio the device never produced, indistinguishable from
        // the real tail in a waveform.
        val resampler = AudioResampler(fromSampleRateHz = 16_000)
        resampler.resample(AudioSignals.speech(takeSamples))
        val first = resampler.drain()
        val second = resampler.drain()

        assertTrue("the first drain emitted nothing", first.isNotEmpty())
        assertEquals(
            "the second drain emitted ${second.size} samples; the first had " +
                "already reset the resampler, so there was nothing left to emit",
            0,
            second.size,
        )
    }

    @Test
    fun `input after a drain is unaffected by the take that was drained`() {
        // A restarted capture must be a take of its own. The drain leaves the
        // resampler holding the previous take's last samples and the fractional
        // position its final read point sat at; if either survived, the next
        // take's first samples would be interpolated against audio that belongs
        // to the take before it — the previous take's last phoneme smeared into
        // this take's first.
        val resampler = AudioResampler(fromSampleRateHz = 16_000)
        resampler.resample(AudioSignals.speech(takeSamples))
        resampler.drain()

        val fresh = AudioSignals.speech(takeSamples)
        val drainedStart = resampler.resample(fresh)
        val cleanStart = AudioResampler(fromSampleRateHz = 16_000).resample(fresh)

        assertEquals(
            "the take after a drain gave ${drainedStart.size} samples where a " +
                "first take gives ${cleanStart.size}",
            cleanStart.size,
            drainedStart.size,
        )
        assertTrue(
            "the take after a drain differs from a first take by up to " +
                "${maxDifference(drainedStart, cleanStart)}, so the drained " +
                "take's tail or its fractional read position carried over",
            maxDifference(drainedStart, cleanStart) < 0.01f,
        )
    }

    @Test
    fun `the total count is exact at every take length and every rate`() {
        // The contract the drain exists to keep: a take of N input samples is
        // exactly ceil(N * to / from) output samples across resample and drain
        // together, for every N and every rate pair. It is pinned over the
        // whole grid rather than at a few lengths because the length that
        // matters is the one where N does not divide: a drain that placed its
        // last read point on a rounded-off position, or emitted one read point
        // too many, is off by a sample only at those lengths and nowhere else.
        // Every length from 1 to 499 is checked at all five rate pairs, so a
        // resampler that is a sample out at some N in there cannot pass.
        //
        // The count is over both halves of the take. Checking resample alone
        // would pass for a drain that emits the right total by giving back a
        // sample the take never had, and checking the drain alone would pass
        // for one that pads; only the sum is the contract.
        val ratePairs = listOf(8_000, 16_000, 22_050, 44_100, 48_000)
        val mismatches = ArrayList<String>()
        for (from in ratePairs) {
            for (inputSize in 1..499) {
                val resampler = AudioResampler(fromSampleRateHz = from)
                val body = resampler.resample(
                    AudioSignals.tone(
                        inputSize,
                        hz = 440.0,
                        amplitude = 0.5f,
                        sampleRateHz = from.toDouble(),
                    ),
                )
                val tail = resampler.drain()
                val expected = ceilDiv(inputSize * AudioFormat.SAMPLE_RATE_HZ, from)
                if (body.size + tail.size != expected) {
                    mismatches.add(
                        "$inputSize in at $from gave ${body.size + tail.size} " +
                            "where $expected was called for",
                    )
                }
            }
        }
        assertEquals(
            "a take of N samples must come out at exactly ceil(N * 16000 / from) " +
                "for every N from 1 to 499 and every rate pair, but these lengths " +
                "came out a sample out: ${mismatches.take(8)}",
            0,
            mismatches.size,
        )
    }

    /** [n] samples in at 48 kHz is `ceil(n / 3)` samples out at 16 kHz. */
    private fun ceilDiv(n: Int, divisor: Int): Int = (n + divisor - 1) / divisor

    /**
     * A take long enough that half a kernel is a rounding error against it, and
     * not so long that the test spends its time in the resampler.
     */
    private val takeSamples = 4_800

    /**
     * How far a drained sample of a constant take may sit from the constant.
     *
     * Not floating-point noise. A windowed sinc evaluated in single precision
     * over 32 taps of a value returns that value to about one part in 10^6, and
     * a read point's sub-sample position moves the weights by a fraction of a
     * percent. A drain that has lost the samples behind the read point reads
     * them as zero, which at a rate of one-in-three puts roughly a third of the
     * window's weight on silence and drops the sample to about 0.33 of the
     * constant — an order of magnitude past this bound and unmistakable.
     */
    private val dcLevelTolerance = 0.02f

    private fun maxDifference(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var worst = 0f
        for (i in 0 until n) worst = maxOf(worst, kotlin.math.abs(a[i] - b[i]))
        return worst
    }
}
