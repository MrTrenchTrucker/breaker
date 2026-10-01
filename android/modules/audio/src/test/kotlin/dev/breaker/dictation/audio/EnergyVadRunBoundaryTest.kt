package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The run length the detector commits at, from both sides of the boundary.
 *
 * [EnergyVad.MIN_RUN_FRAMES] frames of consecutive speech are what open a take,
 * and that many is the boundary itself — so a comparison written as `>` instead
 * of `>=` demands one frame more and changes what the detector does without
 * changing anything anyone can read: the short run at the head of the fixture
 * stops opening a take, and the dictation behind it becomes the reported start
 * instead, shifting the reported speech start by the length of the run. That is
 * a clipped onset in the audio and nothing at all in the return value's
 * shape — which is why the mutant survived every other test in the suite.
 *
 * So both sides of the boundary are pinned here, one frame apart, and the
 * fixtures are sized off [EnergyVad.MIN_RUN_FRAMES] itself rather than off a
 * number typed beside it: raising the constant moves these fixtures with it and
 * keeps them on the boundary, where a fixture hard-coded to three would quietly
 * start asserting whatever the new constant happens to mean.
 */
class EnergyVadRunBoundaryTest {

    @Test
    fun `a run one frame short of the minimum does not open a take`() {
        val runFrames = EnergyVad.MIN_RUN_FRAMES - 1

        val result = EnergyVad(padMs = 0).trim(captureWithLeadingRun(runFrames))

        // Below the minimum the run is not speech yet, so the detector waits
        // past it and opens the dictation behind it instead. The reported start
        // has to be the dictation's own first frame.
        assertEquals(
            "a run of $runFrames frames is one short of the MIN_RUN_FRAMES=" +
                "${EnergyVad.MIN_RUN_FRAMES} the detector requires, so it must not " +
                "open a take, but the take was reported as starting at " +
                "${result.speechStartMs}ms instead of at the dictation's " +
                "$dictationStartMsms",
            dictationStartMsms,
            result.speechStartMs,
        )
    }

    @Test
    fun `a run exactly the minimum long does open a take`() {
        val runFrames = EnergyVad.MIN_RUN_FRAMES

        val result = EnergyVad(padMs = 0).trim(captureWithLeadingRun(runFrames))

        // The far side of the same boundary, and the one `>=` is written for:
        // exactly MIN_RUN_FRAMES frames of speech IS a run, so the take opens
        // THERE, at the run's own first frame. A comparison of `>` instead of
        // `>=` would demand one frame more, this run would no longer qualify,
        // and the reported start would jump to the dictation behind it — a
        // 140 ms error on a fixture whose only job is this comparison.
        assertEquals(
            "a run of $runFrames frames is exactly the MIN_RUN_FRAMES=" +
                "${EnergyVad.MIN_RUN_FRAMES} the detector requires, so it must open " +
                "the take at the run's own first frame (${runStartMs}ms), but the " +
                "take was reported as starting at ${result.speechStartMs}ms",
            runStartMs,
            result.speechStartMs,
        )
    }

    /**
     * A capture of a frame of silence, then [runFrames] frames of speech, then
     * silence, then a dictation long enough to clear the 150 ms minimum on its
     * own.
     *
     * The dictation starts at a FIXED offset whichever the run length is, so
     * the two fixtures above differ in one thing only: how many speech frames
     * precede it. A dictation that moved with the run would make "the detector
     * waited for the dictation" and "the detector opened the run" report the
     * same millisecond on one of the two sides, and the boundary would stop
     * being distinguishable at all.
     *
     * The leading silence primes the tracked floor from the capture itself, so
     * the run is judged on its own level rather than against a floor its own
     * first frame taught the detector; without it neither test would reach the
     * run-length rule. The gap between the run and the dictation is however many
     * frames are left of the eight-frame lead-in once the run has taken its own —
     * five when the run is MIN_RUN_FRAMES - 1, four when it is MIN_RUN_FRAMES.
     * Either is long enough to break the run and short enough that the hangover
     * bridges it, so the trailing silence does not become the thing under test.
     */
    private fun captureWithLeadingRun(runFrames: Int): FloatArray {
        val pcm = FloatArray(dictationStartSamples + dictationSamples)
        AudioSignals.silence(frameSamples).copyInto(pcm, 0)
        AudioSignals.speech(runFrames * frameSamples)
            .copyInto(pcm, frameSamples)
        AudioSignals.silence(dictationStartSamples - frameSamples - runFrames * frameSamples)
            .copyInto(pcm, frameSamples + runFrames * frameSamples)
        AudioSignals.speech(dictationSamples).copyInto(pcm, dictationStartSamples)
        return pcm
    }

    /** 20 ms analysis window at 16 kHz. */
    private val frameSamples = 320

    /**
     * Where the dictation starts: 160 ms in, at 16 kHz.
     *
     * Frame 1 (20 ms) is the leading silence, frames 1..MIN_RUN_FRAMES are the
     * run, and the frames between the end of the longest run the tests build
     * and this offset are the gap that breaks it.
     */
    private val dictationStartSamples = 2_560

    /** 300 ms of dictation, comfortably past the 150 ms a take has to reach. */
    private val dictationSamples = 4_800

    private val dictationStartMsms = dictationStartSamples * 1000L / 16_000L

    /** The leading silence puts the run's first frame here. */
    private val runStartMs = frameSamples * 1000L / 16_000L
}
