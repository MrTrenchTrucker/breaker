package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A lightweight energy VAD.
 *
 * Speech is decided from frame energy against a noise floor the detector tracks
 * as it goes, rather than from a fixed threshold: a truck cab at 60 mph and a
 * parked van at night differ by more than any constant would survive, but both
 * have a quiet part and a loud part, and the gap between them is the signal.
 *
 * The floor follows the *quietest* recent frames and creeps upwards slowly, so
 * a passing truck lifts it a little and quiet speech still crosses it.
 *
 * ### What "trim" means here
 *
 * [trim] keeps the span between the first and the last speech frame and drops
 * the silence on either side of it. It never keeps the audio *after* the speech
 * and discards the audio *before* it — the dictation is the middle, and an
 * inverted trim returns the room noise instead of what the user said.
 *
 * Frame boundaries are quantised, so [padMs] of padding is added on each side
 * to keep the attack of the first phoneme and the tail of the last one.
 *
 * Not thread-safe: a detector is fed one capture at a time, on one thread.
 */
internal class EnergyVad(
    private val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    /** Analysis window. 20 ms is short enough to catch a plosive, long enough to be stable. */
    frameSizeSamples: Int = DEFAULT_FRAME_SAMPLES,
    /** A frame is speech when it is this far above the tracked floor. */
    private val speechMarginDb: Float = DEFAULT_SPEECH_MARGIN_DB,
    /**
     * The floor may rise by this much per frame, so a loud passage cannot inflate it.
     *
     * Derived from [DEFAULT_FLOOR_RISE_DB_PER_SECOND] and the analysis window
     * rather than stated per frame: a frame is 20 ms at 16 kHz and 6.67 ms at
     * 48 kHz, so one number per frame is one number per second only at the rate
     * it was chosen for.
     */
    private val floorRisePerFrameDb: Float = floorRiseDbPerFrameOf(sampleRateHz, frameSizeSamples),
    /**
     * Speech that ends is held this long before the detector calls it over.
     *
     * Derived from [DEFAULT_HANGOVER_MS] for the same reason: a hangover counted
     * in frames bridges the gap between words at one rate and not at another.
     */
    private val hangoverFrames: Int = framesForMs(DEFAULT_HANGOVER_MS, sampleRateHz, frameSizeSamples),
    /** Padding kept on each side of the retained span, so word edges survive. */
    private val padMs: Long = DEFAULT_PAD_MS,
    /** Speech shorter than this is treated as a cough or a door, not a dictation. */
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    /**
     * The loudest the tracked noise floor is ever allowed to become, and the
     * loudest level that is ever reported as silence.
     *
     * The floor follows the quietest recent frames, so a capture that contains
     * no quiet frames at all would otherwise learn the *speech* as its own
     * background and report the whole take as silence — the dictation
     * discarded, with no error anywhere. This ceiling is the absolute claim
     * that a room is never this loud: whatever the recent frames say, a level
     * above the ceiling is treated as speech.
     *
     * It bounds the speech threshold as well as the floor, which is the half
     * that makes the claim hold. Clamping only the floor would leave the
     * threshold a margin higher than the ceiling, and every level in that band
     * — sustained speech in a loud cab — would be called silence.
     */
    private val maxNoiseFloorDb: Float = DEFAULT_MAX_NOISE_FLOOR_DB,
) : Vad {

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(frameSizeSamples > 0) { "frameSizeSamples must be positive: $frameSizeSamples" }
        require(hangoverFrames >= 0) { "hangoverFrames cannot be negative: $hangoverFrames" }
        require(padMs >= 0) { "padMs cannot be negative: $padMs" }
        require(minSpeechMs >= 0) { "minSpeechMs cannot be negative: $minSpeechMs" }
        require(maxNoiseFloorDb < 0f) {
            "maxNoiseFloorDb must sit under full scale or nothing is ever speech: " +
                "$maxNoiseFloorDb dB"
        }
    }

    /** Analysis window, in samples. */
    val frameSize: Int = frameSizeSamples

    private val padSamples: Int = (padMs * sampleRateHz / 1000L).toInt()
    private val minSpeechSamples: Long = minSpeechMs * sampleRateHz / 1000L

    // Floor tracking. Written by the capture thread only.
    private var noiseFloorDb: Float = INITIAL_FLOOR_DB
    private var primed: Boolean = false

    /**
     * Reset the noise floor.
     *
     * Called between captures: a floor learned in a loud cab would swallow the
     * quiet speech of the next take in a parked van.
     */
    fun reset() {
        noiseFloorDb = INITIAL_FLOOR_DB
        primed = false
    }

    override fun isSpeech(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return false
        val level = levelDbOf(frame)
        if (!primed) {
            noiseFloorDb = level
            primed = true
        }
        noiseFloorDb = trackFloor(noiseFloorDb, level)
        return level > speechThresholdDb()
    }

    /**
     * The level a frame has to beat to count as speech.
     *
     * The margin alone is not the whole threshold. The tracked floor is already
     * clamped to [maxNoiseFloorDb], so in a loud cab the sum of the two sits
     * *above* the ceiling by the margin's width — and a level between the
     * ceiling and the sum, which the ceiling's own contract calls speech, is
     * reported as silence. Sustained speech is exactly that level: it is loud
     * enough that the floor learns it as background, and quiet enough to sit
     * under the sum. Capping the threshold at the ceiling is what makes the
     * ceiling mean what it says — nothing above it is ever the floor, so
     * nothing above it can be silence either — and it leaves the margin doing
     * its job in a quiet room, where the two are far apart.
     */
    private fun speechThresholdDb(): Float = min(noiseFloorDb + speechMarginDb, maxNoiseFloorDb)

    override fun trim(pcm: FloatArray): TrimResult {
        if (pcm.isEmpty()) return TrimResult(FloatArray(0), 0, 0, 0, 0, hasSpeech = false)

        reset()
        val frameCount = pcm.size / frameSize
        if (frameCount == 0) {
            // Shorter than one analysis window: there is nothing to decide on,
            // so the capture is handed back untouched rather than discarded.
            return TrimResult(pcm.copyOf(), 0, durationMsOf(pcm.size), 0, 0, hasSpeech = true)
        }

        val speech = BooleanArray(frameCount)
        for (frame in 0 until frameCount) {
            val from = frame * frameSize
            speech[frame] = isSpeech(pcm.copyOfRange(from, from + frameSize))
        }

        val first = firstSustainedSpeechFrame(speech) ?: return silenceRemoved(pcm)
        val last = lastSpeechFrameWithHangover(speech, first)
        if ((last - first + 1) * frameSize < minSpeechSamples) return silenceRemoved(pcm)

        // The span between the first and the last speech frame, padded outwards
        // and clipped to the capture. This is the dictation; everything outside
        // it is the room.
        val start = max(0, first * frameSize - padSamples)
        val end = min(pcm.size, (last + 1) * frameSize + padSamples)
        val kept = pcm.copyOfRange(start, end)
        return TrimResult(
            pcm = kept,
            speechStartMs = first * frameSize * 1000L / sampleRateHz,
            speechEndMs = ((last + 1) * frameSize) * 1000L / sampleRateHz,
            leadingSilenceMs = start * 1000L / sampleRateHz,
            trailingSilenceMs = (pcm.size - end) * 1000L / sampleRateHz,
            hasSpeech = true,
        )
    }

    /**
     * Move the floor towards [level]: straight down when the frame is quieter,
     * slowly up when it is louder.
     */
    private fun trackFloor(floorDb: Float, levelDb: Float): Float =
        min(
            if (levelDb < floorDb) levelDb else min(levelDb, floorDb + floorRisePerFrameDb),
            maxNoiseFloorDb,
        )

    /**
     * The first frame of the first run of speech at least [MIN_RUN_FRAMES] long.
     *
     * A single loud frame is a knock, not the start of a dictation, so a run is
     * required before the detector commits.
     */
    private fun firstSustainedSpeechFrame(speech: BooleanArray): Int? {
        var run = 0
        for (frame in speech.indices) {
            if (speech[frame]) {
                run++
                if (run >= MIN_RUN_FRAMES) return frame - run + 1
            } else {
                run = 0
            }
        }
        return null
    }

    /** The last speech frame, extended forwards through a gap no longer than the hangover. */
    private fun lastSpeechFrameWithHangover(speech: BooleanArray, first: Int): Int {
        var last = first
        var gap = 0
        for (frame in first + 1 until speech.size) {
            if (speech[frame]) {
                last = frame
                gap = 0
            } else {
                gap++
                if (gap > hangoverFrames) break
            }
        }
        return last
    }

    /** A capture with no speech in it: everything removed, and it says so. */
    private fun silenceRemoved(pcm: FloatArray): TrimResult =
        TrimResult(
            pcm = FloatArray(0),
            speechStartMs = 0,
            speechEndMs = 0,
            leadingSilenceMs = 0,
            trailingSilenceMs = durationMsOf(pcm.size),
            hasSpeech = false,
        )

    private fun durationMsOf(samples: Int): Long = samples * 1000L / sampleRateHz

    private fun levelDbOf(frame: FloatArray): Float {
        var sum = 0.0
        frame.forEach { sum += (it * it).toDouble() }
        val rms = sqrt(sum / frame.size)
        return if (rms <= SILENCE_RMS) SILENCE_FLOOR_DB else (20.0 * log10(rms)).toFloat()
    }

    companion object {
        /**
         * The analysis window, in samples: 20 ms at 16 kHz.
         *
         * Stated in samples rather than derived from the rate because the window
         * has to be a whole number of samples at whatever rate the caller runs.
         * Every other duration in this class is converted from milliseconds, so
         * an overridden window keeps them in milliseconds as well: the rise and
         * the hangover are read off this window rather than counted out on it.
         */
        const val DEFAULT_FRAME_SAMPLES: Int = 320

        /** Speech must clear the floor by this many decibels. */
        const val DEFAULT_SPEECH_MARGIN_DB: Float = 8f

        /**
         * How fast the floor is allowed to rise, in decibels per second.
         *
         * Per second and not per frame so the floor climbs at the same rate
         * whatever the window is: 25 dB/s is 0.5 dB on a 20 ms frame, 1.0 dB on a
         * 40 ms one and 0.167 dB on a 6.67 ms one.
         */
        const val DEFAULT_FLOOR_RISE_DB_PER_SECOND: Float = 25f

        /** 200 ms of hangover bridges the gap between words. */
        const val DEFAULT_HANGOVER_MS: Long = 200L

        /** Padding on each side of the retained span. */
        const val DEFAULT_PAD_MS: Long = 120L

        /** Below 150 ms is not a dictation. */
        const val DEFAULT_MIN_SPEECH_MS: Long = 150L

        /** Consecutive speech frames needed before the detector commits. */
        const val MIN_RUN_FRAMES: Int = 3

        /** An RMS at or under this counts as digital silence. */
        private const val SILENCE_RMS = 1e-9

        /** Where a silent frame is reported on the decibel scale. */
        private const val SILENCE_FLOOR_DB = -120f

        private const val INITIAL_FLOOR_DB = -60f

        /**
         * The loudest a tracked floor may become, and the loudest level that
         * may be reported as silence: -25 dBFS, about 5% of full scale. Room
         * tone, a fan and a quiet engine all sit well under it, and a voice
         * does not.
         */
        const val DEFAULT_MAX_NOISE_FLOOR_DB: Float = -25f

        /**
         * Whole analysis windows in [ms], at least one.
         *
         * Rounded up, because a hangover that lands a fraction of a window short
         * bridges less than [ms] and the shortfall is in the direction that cuts
         * a word in half. Zero is not a meaningful answer either: it would drop
         * the hangover rather than shorten it, so a caller asking for no
         * hangover says so with the field, not with a duration.
         *
         * A window or a rate that is not positive has no length to convert, and
         * that combination is refused at construction — but the refusal lives in
         * the constructor body, which runs after this default is evaluated, so
         * the shortest hold is returned here and the refusal is what the caller
         * is left with. Throwing from here would report the wrong field: this
         * helper is only reached through the constructor, which names every
         * field it refuses and why.
         */
        private fun framesForMs(ms: Long, sampleRateHz: Int, frameSizeSamples: Int): Int {
            if (frameSizeSamples <= 0 || sampleRateHz <= 0) return 1
            val denominator = frameSizeSamples * 1000L
            return maxOf(1L, (ms * sampleRateHz + denominator - 1) / denominator).toInt()
        }

        /**
         * The per-frame step that climbs at [DEFAULT_FLOOR_RISE_DB_PER_SECOND]
         * over one window.
         *
         * Derived from the window rather than stated per frame so the climb is
         * the same speed in wall-clock terms at every sample rate: the rate is
         * per second and a frame lasts `frameSizeSamples / sampleRateHz` of one,
         * so on a 6.67 ms window at 48 kHz the same 25 dB/s is 0.167 dB a frame,
         * not the 0.5 dB that 25 dB/s happens to be at 16 kHz.
         *
         * Tolerates a non-positive rate for the same reason as
         * [framesForMs]: there is no frame duration to scale by, the value is
         * never read because construction refuses that rate, and a rate of zero
         * would otherwise make the division non-finite.
         */
        private fun floorRiseDbPerFrameOf(
            sampleRateHz: Int,
            frameSizeSamples: Int,
        ): Float {
            if (sampleRateHz <= 0) return 0f
            return DEFAULT_FLOOR_RISE_DB_PER_SECOND * frameSizeSamples.toFloat() / sampleRateHz.toFloat()
        }
    }
}
