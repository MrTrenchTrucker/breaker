package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Brings captured audio to the one shape everything downstream expects:
 * [AudioFormat.SAMPLE_RATE_HZ] hertz, one channel, float PCM.
 *
 * The card's gotcha says this conversion belongs at the boundary, not
 * downstream, and that is where it lives: [MicCapture] runs every frame
 * through here before the frame is handed to anybody, so no consumer has to
 * wonder what rate it was given.
 *
 * ### Why a windowed-sinc resampler
 *
 * Dropping samples to reach 16 kHz folds everything above 8 kHz back into the
 * band speech lives in, and speech is not the only thing up there — a hint of
 * it turns consonants into noise. A windowed-sinc kernel is a low-pass filter
 * built into the interpolation, so the decimation is clean. Linear
 * interpolation would be shorter and would leave an audible dullness on
 * sibilants, which is the first thing a transcription engine complains about.
 *
 * ### Why it is stateful
 *
 * A resampler that starts each call from scratch puts a discontinuity at every
 * frame boundary — a click every 20 ms. This one carries its filter history,
 * the input samples no output has claimed yet, and its fractional read
 * position across calls, so feeding it a signal in small pieces gives the same
 * samples as feeding it the whole signal at once. [reset] exists for the next
 * capture, because carrying history across takes would smear one take's last
 * phoneme into the next take's first.
 *
 * That promise has one edge to it, at the end of a take. A windowed sinc
 * reaches half its width ahead of the read point as well as behind it, so a
 * read point whose forward taps have not arrived cannot be interpolated yet.
 * This resampler holds it back — output is delayed by half a kernel and the
 * unconsumed tail is carried into the next call — which is why small pieces and
 * one whole signal agree. What happens to a tail no further call will ever
 * arrive for is [drain]'s business, and it is the reason a take is not short.
 *
 * ### The end of a take
 *
 * [drain] emits the read points whose time lies inside the take, windowed over
 * a signal that is zero past its last sample. The count is exact: a take of N
 * input samples yields exactly `ceil(N * toSampleRateHz / fromSampleRateHz)`
 * output samples across [resample] and [drain] together, and the last fraction
 * of a millisecond of them are computed over that zero-extended window. The
 * drain adds no duration and pads no frame: it adds exactly the read points
 * the take's own length implies and no others.
 *
 * That is a different thing from audio the device never produced. A device
 * that drops reads, overruns, or reports silence takes samples out of the take
 * from the outside, and nothing here puts them back — a take is short by
 * whatever its device never delivered, and the drain does not pretend
 * otherwise.
 *
 * Not thread-safe: one instance, one capture, one thread. [drain] runs on that
 * same thread, at the end of the take, and never from a second one.
 */
internal class AudioResampler(
    private val fromSampleRateHz: Int,
    private val toSampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    /** Interpolation taps per output sample. More is cleaner and slower. */
    private val taps: Int = DEFAULT_TAPS,
) {
    init {
        require(fromSampleRateHz > 0) { "fromSampleRateHz must be positive: $fromSampleRateHz" }
        require(toSampleRateHz > 0) { "toSampleRateHz must be positive: $toSampleRateHz" }
        require(taps >= MIN_TAPS) {
            "taps must be at least $MIN_TAPS for a windowed sinc, not $taps"
        }
        require(taps % 2 == 0) { "taps must be even so the kernel is centred: $taps" }
    }

    /** Output rate this resampler produces. */
    val outputSampleRateHz: Int get() = toSampleRateHz

    /** Input samples consumed per output sample. */
    private val step: Double = fromSampleRateHz.toDouble() / toSampleRateHz

    /**
     * The [taps] input samples immediately before [pending], oldest first. Zero
     * until real audio arrives, which is the filter's warm-up.
     */
    private val history = FloatArray(taps)

    /**
     * Input samples that have arrived but no output sample has been read from
     * them yet, because the window reaches ahead of the read point as well as
     * behind it and those samples were still in flight at the end of the last
     * call. They become the head of the next call's audio rather than being
     * dropped.
     */
    private var pending: FloatArray = EMPTY

    /**
     * How far into [pending] the next output sample sits, in input samples.
     *
     * It is the fraction left over from the last call — the read point does not
     * land on a sample — so the kernel keeps its sub-sample position across the
     * call boundary instead of restarting on the next block's first sample.
     */
    private var position: Double = 0.0

    /**
     * Emit the output samples the take still owes, and reset.
     *
     * [resample] holds back any read point whose forward half of the window
     * has not arrived, because interpolating it from taps that are not there —
     * while still dividing by their weight — puts a step in the audio. That is
     * the right call in the middle of a capture, where the samples are one more
     * read away. At the end of a take they never arrive, and the held-back
     * read points would be lost: the take would come up short by the width of
     * half a kernel, which at the default 32 taps is 15 output samples at 1:1
     * and 5 at 48 kHz -> 16 kHz.
     *
     * So this emits them, windowed over a signal that is zero past the take's
     * last sample. The read points it emits are exactly those whose time lies
     * inside the take — the same read points, at the same fractional positions,
     * that a signal this long would have produced if the caller had handed the
     * whole thing over at once — and no others. Nothing is added to the take's
     * duration and no frame is padded: the take is the length it was.
     *
     * The count is exact. A take of N input samples yields
     * `ceil(N * toSampleRateHz / fromSampleRateHz)` output samples across
     * [resample] and this together — N at a rate of one, and a third of N when
     * 48 kHz is brought down to 16 kHz, whichever way N falls. How many of
     * them are computed over the zero-extended window rather than over real
     * audio depends on where the take's last read point falls, so it is a
     * property of the take's length and not a fixed number; what is fixed is
     * the total, which this never moves.
     *
     * At a rate of one the window is centred on a sample and every other tap
     * lands an exact multiple of the step away, where the sinc has its nulls, so
     * the drained samples are the input tail sample for sample rather than an
     * approximation of it. Where the step does not divide the window the
     * drained samples are the zero-extended interpolation, which is why they are
     * not simply the last input samples.
     *
     * Calling this again yields nothing: it resets the history, the carry and
     * the position, so a second drain has no read points left to emit and a
     * capture that starts afterwards is unaffected by this take's tail.
     *
     * Not thread-safe, like the rest of this class: run it on the thread that
     * owns the resampler, at the end of the take.
     */
    internal fun drain(): FloatArray {
        val half = taps / 2
        val available = pending.size
        if (available == 0) {
            reset()
            return FloatArray(0)
        }

        // The take ends at the last sample in [pending]. The window reaches
        // half a kernel further forward than that, and past the end of a take
        // the signal is silence, so the forward half of the window is given
        // zeros to read rather than being left off the end of the buffer and
        // skipped. The buffer is padded by exactly `half`, which is the widest
        // the window ever reaches, so every tap of every read point emitted
        // below lands inside it and the normalisation sees the whole window.
        val combined = FloatArray(taps + available + half)
        System.arraycopy(history, 0, combined, 0, taps)
        System.arraycopy(pending, 0, combined, taps, available)

        // `position` is the next read point within [pending], and a read point
        // belongs to this take while it sits inside it: past `available` its
        // time is beyond the last sample the take ever had, and emitting it
        // would add duration the take does not contain.
        val estimated = ((available - position) / step).toInt() + 2
        val out = FloatArray(estimated)
        var count = 0
        var local = position
        while (local < available) {
            val index = local.toInt()
            out[count++] = interpolate(combined, index, local - index)
            local += step
        }

        reset()
        return if (count == out.size) out else out.copyOf(count)
    }

    /** Forget the history, for the next capture. */
    fun reset() {
        history.fill(0f)
        pending = EMPTY
        position = 0.0
    }

    /**
     * Convert [input] to [toSampleRateHz] hertz.
     *
     * The output is [input] scaled by the rate ratio, less the filter's
     * warm-up at the start of a capture (where there is no history behind the
     * read point) and the filter's look-ahead at the end of a take (where there
     * is nothing ahead of it). A caller accumulating a whole capture should not
     * expect the two lengths to match exactly.
     */
    fun resample(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)

        val half = taps / 2

        // The samples the kernel can read: the history behind, then the tail
        // this resampler has been holding, then this call's input. Input index
        // `i` is `combined[i + taps]`, so the read point and its position
        // carry over from the last call without rebasing.
        val available = pending.size + input.size
        val combined = FloatArray(taps + available)
        System.arraycopy(history, 0, combined, 0, taps)
        System.arraycopy(pending, 0, combined, taps, pending.size)
        System.arraycopy(input, 0, combined, taps + pending.size, input.size)

        val estimated = (available / step).toInt() + 2
        val out = FloatArray(estimated)
        var count = 0
        var local = position
        // Only a read point whose whole window has arrived is emitted. The
        // kernel reaches `half` samples ahead as well as behind, so a read
        // point within `half` of the end of what has arrived would interpolate
        // from samples that are not there — the taps it cannot reach are
        // skipped while their weights are still counted, which divides a
        // truncated sum by the full sum and puts a step of about 0.17 at the end
        // of every capture block. Such a read point waits: its samples arrive
        // in the next call, which is what carrying [pending] is for.
        while (local.toInt() + half <= available) {
            val index = local.toInt()
            out[count++] = interpolate(combined, index, local - index)
            local += step
        }

        // The next read point, and everything from it to the end of what has
        // arrived, is kept for the next call: the position within the sample
        // it sits on, the samples ahead of it that no output has claimed, and
        // the `taps` samples behind it, which are the next call's history. A
        // read point beyond the end of the audio (a wide step can land there)
        // leaves the tail empty and the position counting on past it, so the
        // next call picks the stream up where it left off.
        val nextRead = minOf(local.toInt(), available)
        position = local - nextRead
        pending = combined.copyOfRange(taps + nextRead, combined.size)
        val kept = minOf(taps, nextRead)
        history.fill(0f)
        if (kept > 0) {
            System.arraycopy(combined, nextRead + taps - kept, history, taps - kept, kept)
        }

        return if (count == out.size) out else out.copyOf(count)
    }

    /**
     * The kernel-smoothed value at input index [index] + [fraction].
     *
     * [combined] is the history followed by this call's input, so input index
     * `i` is `combined[i + taps]`. The kernel reaches back `taps / 2` samples
     * either side of the read point, which is where the history matters.
     */
    private fun interpolate(combined: FloatArray, index: Int, fraction: Double): Float {
        val half = taps / 2
        var sum = 0f
        var weightSum = 0f
        for (k in 0 until taps) {
            val sampleIndex = index + k - half
            val distance = fraction + (half - k).toDouble()
            val weight = (sinc(distance / step) * blackmanHarris(distance / half.toDouble()))
                .toFloat()
            // Every tap the window reaches for is applied, and every applied
            // tap counts towards the normalisation, so the two always agree.
            //
            // The bound is on the combined buffer, not on this call's own
            // samples: the kernel reaches back `half` samples either side of
            // the read point, so at a rate change — where the read point lands
            // between input samples rather than on one — the taps behind it
            // fall at a negative `sampleIndex` and are the filter's history.
            // Excluding those skipped the two dominant taps of the window
            // while still dividing by their weight, which put a step of about
            // 0.17 at every call boundary: a click every capture block.
            if (sampleIndex + taps in 0 until combined.size) {
                sum += combined[sampleIndex + taps] * weight
            }
            weightSum += weight
        }
        return if (weightSum == 0f) {
            combined[(index + taps).coerceIn(0, combined.size - 1)]
        } else {
            sum / weightSum
        }
    }

    private fun sinc(x: Double): Double =
        if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

    /**
     * A Blackman-Harris window that tapers the kernel to zero at its edges, so
     * the ends add nothing.
     *
     * [x] runs from -1 at one edge to +1 at the other, with 0 at the kernel's
     * centre, and the window is 1 at the centre and 0 at both edges. All four
     * cosine terms carry a plus sign because of where the centre sits: the sum
     * is a0+a1+a2+a3 = 1 there and a0-a1+a2-a3 = 0 at the edges. Negating the
     * odd terms inverts it — a window of zero at the centre and one at the
     * edges, which keeps the kernel's nulls and throws away the sample being
     * interpolated, and turns the filter into a comb.
     */
    private fun blackmanHarris(x: Double): Double {
        val a = 0.35875
        val b = 0.48829
        val c = 0.14128
        val d = 0.01168
        if (x <= -1.0 || x >= 1.0) return 0.0
        return a + b * cos(PI * x) + c * cos(2 * PI * x) + d * cos(3 * PI * x)
    }

    companion object {
        /** 32 taps: clean at 48 k -> 16 k, and cheap enough for a capture thread. */
        const val DEFAULT_TAPS: Int = 32

        /** Below this the kernel is not worth interpolating with. */
        const val MIN_TAPS: Int = 8

        private val EMPTY = FloatArray(0)
    }
}

/**
 * Downmixes interleaved multi-channel PCM to mono.
 *
 * Channels are averaged rather than one being picked: a phone held at an angle
 * puts more of the voice in one channel, and averaging keeps the level even
 * whichever way it is held.
 */
internal class ChannelDownmixer(private val channelCount: Int) {
    init {
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
    }

    /**
     * Average [input] down to mono.
     *
     * A trailing partial frame (fewer samples than a whole frame) is dropped
     * rather than averaged over channels it does not have, so the output is
     * always a whole number of frames of real audio.
     */
    fun downmix(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        if (channelCount == 1) return input.copyOf()
        val frameCount = input.size / channelCount
        val out = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0f
            for (channel in 0 until channelCount) {
                sum += input[frame * channelCount + channel]
            }
            out[frame] = sum / channelCount
        }
        return out
    }
}
