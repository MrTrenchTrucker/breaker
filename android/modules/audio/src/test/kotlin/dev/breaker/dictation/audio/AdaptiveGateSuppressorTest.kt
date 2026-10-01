package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The downward gate, and the noise floor that decides what it compares against.
 *
 * ### Why these are tested at all
 *
 * [AdaptiveGateSuppressor] is public and in the module's registry, and until this
 * round it had no test whatsoever. Two arithmetic defects lived in it:
 *
 * - the gate margin was converted as `exp(db * ln(10))` — that is `10^db`, not the
 *   amplitude ratio `10^(db/20)`. At the 10 dB default that put `openAt` at
 *   `floor * 1e10`, so for any sample inside [-1, 1] the gate could never open and
 *   every frame was attenuated by the closed gain. The sibling `dbToGain` already
 *   did the division correctly, which is what made the wrong one look plausible.
 * - the floor moved by `level + (floor - level) * k`, spending each coefficient as
 *   the fraction the floor KEEPS, while the constants document `k` as the fraction
 *   it FOLLOWS. The louder branch therefore ran at 0.02 — effectively frozen — and
 *   the floor chased a quiet frame at 0.4, the exact inverse of the KDoc.
 *
 * ### Why these tests measure the GATE rather than the floor
 *
 * `trackFloor` is private and the floor has no accessor, so a test that observed it
 * directly would have to invert both the per-sample gain glide and the high-pass to
 * recover it. An earlier attempt did exactly that and produced a measurement that
 * could not distinguish a working gate from a broken one.
 *
 * Instead every assertion here is on **the gain the suppressor actually applies**,
 * which is the contract a caller depends on: a frame at the floor comes back at the
 * closed gain, a frame a margin above it comes back essentially unattenuated.
 *
 * ### Why the measurement is a normalised ratio
 *
 * The output is the high-passed signal times the gate gain, and the high-pass scales
 * its own output by an alpha of roughly 0.03 — so a raw output peak says nothing
 * about the gate on its own. Every figure here is therefore
 *
 *     impliedGateGain = (outputPeak / inputPeak) / highPassAlpha
 *
 * which is the gate gain alone. Measured: a frame at the floor reads 0.126, which is
 * `dbToGain(-18)` to three decimals, and a frame 10 dB above it reads 0.95.
 *
 * A 1 kHz sine is used rather than a square wave so the input peak is exact and the
 * signal sits well inside the passband. Frames are fed one at a time, as a capture
 * would, and the FIRST frame after priming is the one measured — later frames let the
 * floor creep toward a sustained level, which is the release behaviour under test
 * elsewhere and would blur the gate's own decision.
 */
class AdaptiveGateSuppressorTest {

    private val sampleRate = 16_000
    private val frameSamples = 800 // 50 ms, past both smoothing windows
    private val highPassAlpha =
        AdaptiveGateSuppressor.highPassCoefficient(sampleRate, 80f)

    /** A sine of constant absolute amplitude [a]; its peak is exactly [a]. */
    private fun frame(amplitude: Float): FloatArray =
        FloatArray(frameSamples) {
            (amplitude * sin(2.0 * Math.PI * 1000.0 * it / sampleRate)).toFloat()
        }

    /**
     * The gate gain applied to the next frame of amplitude [peak], isolating the
     * gate from the high-pass.
     */
    private fun impliedGateGain(
        suppressor: AdaptiveGateSuppressor,
        peak: Float,
    ): Float {
        val out = suppressor.process(frame(peak))
        return (out.maxOf { abs(it) } / peak) / highPassAlpha
    }

    /** A suppressor whose floor has settled on a quiet cab. */
    private fun primedFloor(
        quietFrames: Int = 4,
        quiet: Float = QUIET,
    ): AdaptiveGateSuppressor {
        val suppressor = AdaptiveGateSuppressor(sampleRateHz = sampleRate)
        repeat(quietFrames) { suppressor.process(frame(quiet)) }
        return suppressor
    }

    /** [db] dB of amplitude above [base]. */
    private fun dbAbove(base: Float, db: Double): Float =
        (base * Math.pow(10.0, db / 20.0)).toFloat()

    @Test
    fun `a frame at the floor is attenuated by exactly the closed gain`() {
        val gain = impliedGateGain(primedFloor(), QUIET)
        val expected = AdaptiveGateSuppressor.dbToGain(CLOSED_DB.toFloat())

        assertEquals(
            "a frame at the floor must come back at dbToGain(closedGainDb)",
            expected.toDouble(),
            gain.toDouble(),
            0.02,
        )
    }

    @Test
    fun `a frame at the gate margin above the floor opens the gate`() {
        // 10 dB is the default margin. Against the broken conversion that margin
        // became 1e10, so this frame sat far BELOW openAt and the gate could not
        // open at any level inside [-1, 1].
        val gain = impliedGateGain(primedFloor(), dbAbove(QUIET, MARGIN_DB))

        assertTrue(
            "a frame a full ${MARGIN_DB}dB above the floor must open the gate, " +
                "but the implied gate gain was only $gain",
            gain > 0.85f,
        )
    }

    @Test
    fun `a frame well past the margin opens the gate completely`() {
        val gain = impliedGateGain(primedFloor(), dbAbove(QUIET, MARGIN_DB * 2))

        assertTrue(
            "a frame 20 dB above the floor must pass at full gain, but the implied " +
                "gate gain was $gain",
            gain > 0.95f,
        )
    }

    @Test
    fun `a frame below the gate margin stays closed`() {
        // Half the margin: unambiguously background.
        val gain = impliedGateGain(primedFloor(), dbAbove(QUIET, MARGIN_DB / 2))

        assertTrue(
            "a frame below the margin must stay well under full gain, but the " +
                "implied gate gain was $gain",
            gain < 0.8f,
        )
    }

    @Test
    fun `the floor falls quickly when the cab goes quiet`() {
        // The floor must FOLLOW a drop. Prime high, drop to quiet, then read the
        // gate on a fixed 20 dB frame.
        //
        // After ONE quiet frame the floor has fallen by floorAttack (0.4) of the
        // gap, so it is still above the 20 dB frame's level and the gate is still
        // shut — but far less shut than it was. The invariant under test is the
        // DIRECTION and the SPEED, so this asserts the gain rises substantially in
        // one frame and reaches the open gate within a few, which is what the old
        // formula (the floor creeping the wrong way by 2%) cannot do.
        val suppressor = AdaptiveGateSuppressor(sampleRateHz = sampleRate)
        repeat(4) { suppressor.process(frame(dbAbove(QUIET, 20.0))) }
        val beforeDrop = impliedGateGain(suppressor, dbAbove(QUIET, 20.0))

        // One quiet frame: the floor falls by floorAttack (0.4) of the gap.
        suppressor.process(frame(QUIET))
        val afterOne = impliedGateGain(suppressor, dbAbove(QUIET, 20.0))

        // Two more quiet frames complete the fall: 1 - 0.6^3 ~= 94% of the gap.
        suppressor.process(frame(QUIET))
        suppressor.process(frame(QUIET))
        val afterThree = impliedGateGain(suppressor, dbAbove(QUIET, 20.0))

        assertTrue(
            "the floor must fall fast when the cab goes quiet: the 20 dB frame " +
                "read $beforeDrop with the floor high and $afterOne after one quiet " +
                "frame, so one frame should already open it up substantially",
            beforeDrop < 0.2f && afterOne > beforeDrop + 0.15f,
        )
        assertTrue(
            "the floor must finish falling within a few quiet frames, but after " +
                "three the 20 dB frame still read $afterThree instead of opening",
            afterThree > 0.85f,
        )
    }

    @Test
    fun `the floor rises only slowly so sustained speech stays open`() {
        // This is the half that was inverted. With `k` spent as the fraction KEPT,
        // one loud frame dragged the floor ~98% of the way to the new level, so the
        // gate shut on sustained speech within a frame or two. The floor must
        // FOLLOW a rise at only floorRelease (0.02) per frame.
        val suppressor = primedFloor()
        val loud = dbAbove(QUIET, 20.0)

        val first = impliedGateGain(suppressor, loud)
        assertTrue(
            "the first loud frame must open the gate, but the implied gain was $first",
            first > 0.85f,
        )

        // Hold it. At 2% per frame the floor closes only 1 - 0.98^20 ~= 33% of the
        // gap over 20 frames, and the gate is still open. The old formula had shut
        // it on the very first frame.
        var held = first
        repeat(20) { held = impliedGateGain(suppressor, loud) }

        assertTrue(
            "sustained speech must stay open - the floor rises only 2% per frame, " +
                "so after 20 frames the gain should still exceed 0.7, but it was $held",
            held > 0.7f,
        )
    }

    @Test
    fun `output length always equals input length`() {
        val suppressor = primedFloor()
        for (size in intArrayOf(1, 2, 17, 800)) {
            assertEquals(size, suppressor.process(FloatArray(size) { 0.1f }).size)
        }
    }

    private companion object {
        /** A quiet cab: 0.01 sits inside [-1, 1] with room for a margin above it. */
        const val QUIET = 0.01f

        /** The default gate margin, in dB. */
        const val MARGIN_DB: Double = 10.0

        /** The default closed gain, in dB. */
        const val CLOSED_DB: Double = -18.0
    }
}
