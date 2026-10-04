package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * A noise suppressor that needs no native library: a DC blocker, a rumble
 * high-pass, and a downward gate.
 *
 * It is the fallback for a build with no neural suppressor wired in. It is not
 * as good as one — it cannot learn a fan's spectrum — but it removes the two
 * things that wreck a dictation on the road: a DC offset from the mic's
 * analogue front end, and a steady broadband hiss that sits just under the
 * speech the engine is trying to hear.
 *
 * ### Why a gate and not a filter
 *
 * The gate's floor is tracked from the quietest recent frames, the same idea the
 * [EnergyVad] uses. Both therefore agree about what "quiet" means in this
 * environment, which is what stops the suppressor from gating out the pause
 * between two words and the VAD from hearing a syllable it just removed.
 *
 * The gain moves towards its target with different time constants for opening
 * and closing, because a gate that closes instantly chops the attack off a
 * word — a click the listener hears as a click, and the engine hears as a
 * consonant.
 *
 * Output length always equals input length: this is a per-sample filter, so
 * there is nothing to resynchronise.
 *
 * Not thread-safe: one instance, one capture, one thread.
 */
class AdaptiveGateSuppressor(
    private val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    /** Below this frequency, in hertz, the signal is vehicle rumble, not speech. */
    private val highPassHz: Float = DEFAULT_HIGH_PASS_HZ,
    /** Frames below this level update the floor. */
    private val floorAttack: Float = DEFAULT_FLOOR_ATTACK,
    /** How fast the floor is allowed to rise when the cab gets noisy. */
    private val floorRelease: Float = DEFAULT_FLOOR_RELEASE,
    /** How far above the floor a frame must sit to be passed at full gain. */
    private val gateMarginDb: Float = DEFAULT_GATE_MARGIN_DB,
    /** Gain applied to frames judged to be below the floor. */
    private val closedGainDb: Float = DEFAULT_CLOSED_GAIN_DB,
    /** Time to open the gate, in milliseconds. */
    private val openMs: Float = DEFAULT_OPEN_MS,
    /** Time to close the gate, in milliseconds. */
    private val closeMs: Float = DEFAULT_CLOSE_MS,
) : NoiseSuppressor {

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(highPassHz > 0f) { "highPassHz must be positive: $highPassHz" }
        require(closedGainDb < 0f) {
            "closedGainDb must attenuate, not amplify: $closedGainDb dB"
        }
        require(openMs > 0f) { "openMs must be positive: $openMs" }
        require(closeMs > 0f) { "closeMs must be positive: $closeMs" }
    }

    override val isActive: Boolean = true

    /**
     * Passband gain of the high-pass at [highPassHz], so a tone well above the
     * corner comes out at unity rather than a few dB down.
     */
    private val highPassAlpha: Float = highPassCoefficient(sampleRateHz, highPassHz)

    /**
     * The feedback term of the one-pole high-pass.
     *
     * Derived from [sampleRateHz] and [highPassHz] rather than fixed: a fixed
     * coefficient puts the corner at a frequency that depends on the capture
     * rate, so the same suppressor rolls off at a different place on every
     * device and the parameter stops meaning what it says.
     */
    private val highPassPole: Float = highPassPole(sampleRateHz, highPassHz).toFloat()

    /** Per-sample gain smoothing coefficients. */
    private val openCoefficient: Float = smoothingCoefficient(sampleRateHz, openMs)
    private val closeCoefficient: Float = smoothingCoefficient(sampleRateHz, closeMs)

    private val closedGain: Float = dbToGain(closedGainDb)

    // Filter and gate state.
    private var highPassInput = 0f
    private var highPassOutput = 0f
    private var gain = 0f
    private var floorLevel = INITIAL_FLOOR
    private var primed = false

    override fun reset() {
        highPassInput = 0f
        highPassOutput = 0f
        gain = 0f
        floorLevel = INITIAL_FLOOR
        primed = false
    }

    override fun process(frame: FloatArray): FloatArray {
        val out = FloatArray(frame.size)
        if (frame.isEmpty()) return out

        val floor = trackFloor(frame)
        val target = targetGainFor(frame, floor)
        // Smoothing per sample rather than per frame, so a frame boundary never
        // lands mid-glide and clicks.
        frame.forEachIndexed { index, sample ->
            val coefficient = if (target > gain) openCoefficient else closeCoefficient
            gain += (target - gain) * coefficient
            val filtered = highPass(sample)
            out[index] = filtered * gain
        }
        return out
    }

    /** DC blocker then a one-pole high-pass, in place on the running state. */
    private fun highPass(sample: Float): Float {
        val blocked = sample - highPassInput + highPassPole * highPassOutput
        highPassInput = sample
        highPassOutput = blocked
        return blocked * highPassAlpha
    }

    /**
     * Move the noise floor towards this frame's level: quickly down, slowly up.
     *
     * @return the floor after the update, which is also the level this frame is
     *   judged against.
     */
    private fun trackFloor(frame: FloatArray): Float {
        val level = peakOf(frame)
        if (!primed) {
            floorLevel = level
            primed = true
            return floorLevel
        }
        floorLevel += (level - floorLevel) *
            if (level < floorLevel) floorAttack else floorRelease
        return floorLevel
    }

    /**
     * The gain this frame should end at.
     *
     * A frame whose peak is at or below the floor is background and gets the
     * closed gain; one [gateMarginDb] above it is speech and gets full gain;
     * in between, the gain rises with the peak, so a fading syllable is faded
     * rather than chopped.
     */
    private fun targetGainFor(frame: FloatArray, floor: Float): Float {
        val peak = peakOf(frame)
        if (peak <= floor) return closedGain
        val openAt = floor * dbToLinear(gateMarginDb)
        if (peak >= openAt) return 1f
        val span = openAt - floor
        if (span <= 0f) return 1f
        val position = ((peak - floor) / span).coerceIn(0f, 1f)
        return closedGain + (1f - closedGain) * position
    }

    private fun peakOf(frame: FloatArray): Float {
        var peak = 0f
        frame.forEach { peak = max(peak, abs(it)) }
        return peak
    }

    companion object {
        /** 80 Hz: below the fundamental of the lowest voice worth transcribing. */
        const val DEFAULT_HIGH_PASS_HZ: Float = 80f

        /** How fast the floor follows a quieter frame. */
        const val DEFAULT_FLOOR_ATTACK: Float = 0.4f

        /** How fast the floor follows a louder frame. */
        const val DEFAULT_FLOOR_RELEASE: Float = 0.02f

        /** How far above the floor a frame counts as speech. */
        const val DEFAULT_GATE_MARGIN_DB: Float = 10f

        /** What a gated frame is attenuated by. */
        const val DEFAULT_CLOSED_GAIN_DB: Float = -18f

        /** Gate opening time. */
        const val DEFAULT_OPEN_MS: Float = 5f

        /** Gate closing time. */
        const val DEFAULT_CLOSE_MS: Float = 60f

        /**
         * The feedback term of a one-pole high-pass whose corner sits at [hz]
         * when the signal is sampled at [sampleRateHz].
         *
         * A one-pole DC blocker rolls off from the corner at
         * `-ln(pole)` radians per sample, so a pole fixed in source puts the
         * corner at a frequency that moves with the sample rate and is not the
         * one the caller asked for. Inverting that relation is what puts the
         * corner where [hz] says it is.
         */
        fun highPassPole(sampleRateHz: Int, hz: Float): Double =
            exp(-TWO_PI * hz / sampleRateHz)

        private const val TWO_PI = 2.0 * Math.PI
        private const val INITIAL_FLOOR = 0.01f

        /** Where the floor starts before it has heard a frame. */
        const val SILENT_GAIN: Float = 1f

        fun dbToGain(db: Float): Float = exp(db * LN10_OVER_20).toFloat()

        /**
         * [db] decibels of headroom as a linear factor, amplitude to amplitude.
         *
         * 10^(dB/20), the same conversion [dbToGain] uses. A decibel is ten times
         * the base-10 logarithm of a POWER ratio, so an amplitude ratio — the
         * square root of the power ratio — is 10^(dB/20), not 10^dB.
         */
        private fun dbToLinear(db: Float): Float = dbToGain(db)

        private val LN10_OVER_20 = kotlin.math.ln(10.0) / 20.0

        /**
         * The passband gain of the one-pole high-pass at [hz] and
         * [sampleRateHz]: the scalar that scales a DC blocker's output so its
         * passband sits at unity.
         *
         * The raw difference equation `y[n] = x[n] - x[n-1] + pole * y[n-1]`
         * approaches a gain of `2 / (1 + pole)` as the frequency rises, so
         * applying `(1 + pole) / 2` cancels it and leaves the passband at unity
         * whatever the corner. Every frequency below the corner is then shaped
         * by [highPassPole] alone.
         */
        fun highPassCoefficient(sampleRateHz: Int, hz: Float): Float =
            ((1.0 + highPassPole(sampleRateHz, hz)) / 2.0).toFloat()

        /** The one-pole smoothing coefficient for a [timeMs] time constant. */
        fun smoothingCoefficient(sampleRateHz: Int, timeMs: Float): Float {
            val tau = timeMs / 1000.0
            val te = 1.0 / sampleRateHz
            return min(1.0, 1.0 / (1.0 + tau / te)).toFloat()
        }
    }
}
