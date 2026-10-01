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
    /** The floor may rise by this much per frame, so a loud passage cannot inflate it. */
    private val floorRisePerFrameDb: Float = DEFAULT_FLOOR_RISE_DB,
    /** Speech that ends is held this long before the detector calls it over. */
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES,
    /** Padding kept on each side of the retained span, so word edges survive. */
    private val padMs: Long = DEFAULT_PAD_MS,
    /** Speech shorter than this is treated as a cough or a door, not a dictation. */
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    /**
     * The loudest the tracked noise floor is ever allowed to become.
     *
     * The floor follows the quietest recent frames, so a capture that contains
     * no quiet frames at all would otherwise learn the *speech* as its own
     * background and report the whole take as silence — the dictation
     * discarded, with no error anywhere. This ceiling is the absolute claim
     * that a room is never this loud: whatever the recent frames say, a level
     * above the ceiling is treated as speech.
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
        return level > noiseFloorDb + speechMarginDb
    }

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
        /** 20 ms at 16 kHz. */
        const val DEFAULT_FRAME_SAMPLES: Int = 320

        /** Speech must clear the floor by this many decibels. */
        const val DEFAULT_SPEECH_MARGIN_DB: Float = 8f

        /** How fast the floor is allowed to rise, in decibels per frame. */
        const val DEFAULT_FLOOR_RISE_DB: Float = 0.5f

        /** 200 ms of hangover bridges the gap between words. */
        const val DEFAULT_HANGOVER_FRAMES: Int = 10

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
         * The loudest a tracked floor may become: -25 dBFS, about 5% of full
         * scale. Room tone, a fan and a quiet engine all sit well under it, and
         * a voice does not.
         */
        const val DEFAULT_MAX_NOISE_FLOOR_DB: Float = -25f
    }
}
