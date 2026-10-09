// android/modules/gesture - ShakeDetector
// Card: android/modules/gesture/AGENTS.md   Registry: modules.toml [module.android_gesture]
// Owns: the shake detection state machine and its public surface.
// Depends on: Kotlin stdlib; no Android types (a plain JVM test reads it).

package dev.breaker.dictation.gesture

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A shake detector driven by acceleration samples.
 *
 * It turns a stream of magnitude readings into discrete shake events using only the
 * timestamps carried by each sample, so it needs no system clock, no sleeps, and no
 * randomness - and stays deterministic in a plain JVM test. One shake fires at most
 * once per cooldown window. The design constants below are tuned values;
 * they do not reflect verified behaviour on real hardware, which is why
 * device tuning is NOT VERIFIED.
 */
internal class ShakeDetector private constructor(
    /** Invoked once for each detected shake while the detector is running. */
    private val onShake: () -> Unit,
) {

    /**
     * A single acceleration reading with the millisecond at which it was captured.
     * Fed in by any source (the port) in production, or tests directly.
     */
    data class Sample(
        val x: Float,
        val y: Float,
        val z: Float,
        val timestampMs: Long,
    )

    private var started = false
    private var prevAbove = false
    private var prevMag: Double? = null
    private var lastTimestamp: Long? = null
    private var coolUntil: Long? = null
    private val crossings = ArrayDeque<Long>()
    // Low-pass value on the magnitude (g), state carried across samples.
    private var lpMag = 0.0


    private val gravity: Double get() = 9.81

    /**
     * The one-pole high-pass gain for an inter-sample gap of [dtSeconds] seconds:
     * `alpha = dt / (T + dt)` where `T = 1 / (2 * pi * CUTOFF_HZ)`. The gain follows
     * the data rate rather than a fixed clock, so the same physical shake fires at any
     * sampling rate.
     */
    private fun alpha(dtSeconds: Double): Double {
        val t = 1.0 / (2.0 * PI * CUTOFF_HZ) // ~79.6 ms for the configured cutoff
        return if (t + dtSeconds > 0.0) dtSeconds / (t + dtSeconds) else 1.0
    }

    /**
     * Begin accepting samples. While stopped, [feed] ignores every sample; the detection
     * state itself is kept, so starting again resumes from where the detector left off.
     */
    fun start() {
        started = true
    }

    /**
     * Stop accepting samples. The detection state is kept and not cleared: a later
     * [start] resumes from it, and a break longer than [LARGE_GAP_MS] resets state via
     * the gap rule on the first sample fed after the break.
     */
    fun stop() {
        started = false
    }

    /**
     * Advance the detector with one reading, firing [onShake] when a shake is found.
     * While stopped every sample is ignored entirely: no state changes occur.
     *
     * Sample rules (all timestamps are [Sample.timestampMs]):
     * - a timestamp not increasing past the last accepted one is ignored outright;
     * - a gap larger than [LARGE_GAP_MS] resets all detection state, and that sample
     *   then consumes no detection of its own;
     * - the first sample (and the one right after a reset) only seeds the filter;
     * - while in cooldown, crossings may accumulate but nothing fires; when the
     *   cooldown ends the crossing list is cleared and detection starts clean.
     */
    fun feed(sample: Sample) {
        if (!started) return

        val now = sample.timestampMs
        val prior = lastTimestamp

        // Non-increasing timestamp: ignored outright - no state change, no gap from it.
        if (prior != null && now <= prior) return

        // Advance the last-accepted timestamp only on the accepted path, so an
        // ignored sample never advances it and its own gap is measured fresh.
        lastTimestamp = now

        val gap = if (prior != null) now - prior!! else 0L
        val dtSeconds = if (prior != null) (now - prior!!) / 1000.0 else FIRST_SAMPLE_DT_MS / 1000.0

        // A gap past the threshold resets all detection state; this sample is consumed
        // without contributing to detection.
        if (gap > LARGE_GAP_MS) {
            // The gap branch does NOT zero the low-pass term; the next sample re-seeds
            // through the first-sample seed branch. Keep lpMag and reset only the other
            // detection fields.
            prevAbove = false
            prevMag = null
            crossings.clear()
            coolUntil = null
            return
        }

        val mag = sampleMagnitude(sample)

        // First sample (or the one right after a reset): seed the low-pass term.
        if (prevMag == null) {
            lpMag += alpha(dtSeconds) * (mag - lpMag)
            prevAbove = mag - lpMag > THRESHOLD_G
            prevMag = mag
            return
        }

        // One-pole filter, incremental form on the low-pass term.
        lpMag += alpha(dtSeconds) * (mag - lpMag)
        val above = mag - lpMag > THRESHOLD_G
        val crossing = above && !prevAbove
        prevAbove = above

        // In cooldown: crossings may be remembered but nothing fires; the refractory
        // state outlives the crossing list. When it ends, the list starts clean.
        coolUntil?.let { until ->
            if (now < until) {
                if (crossing) {
                    crossings.add(now)
                    while (crossings.isNotEmpty() && now - crossings.first() > WINDOW_MS) {
                        crossings.removeFirst()
                    }
                }
                return
            }
            coolUntil = null
            crossings.clear()
            prevAbove = false
        }

        if (crossing) {
            crossings.add(now)
            while (crossings.isNotEmpty() && now - crossings.first() > WINDOW_MS) {
                crossings.removeFirst()
            }
        }

        if (crossings.size >= CROSSING_COUNT) {
            coolUntil = now + COOLDOWN_MS
            crossings.clear()
            prevAbove = false
            onShake()
        }
    }

    /** The magnitude in g, divided by gravity so it is compared against [THRESHOLD_G]. */
    private fun sampleMagnitude(sample: Sample): Double {
        val sum = (sample.x * sample.x + sample.y * sample.y + sample.z * sample.z).toDouble()
        val m = sqrt(sum) / gravity
        return if (m < 0.0) 0.0 else m
    }

    /**
     * The most recent filtered (high-pass) magnitude, in g's: the AC term the crossing
     * comparator reads. Exposed for the deterministic test that watches a resting phone's
     * term settle to zero; production code never reads it.
     */
    internal val lastAcTerm: Double
        get() = if (prevMag == null) 0.0 else prevMag!! - lpMag

    companion object {
        /** Factory for a detector wired with [onShake]; the DI entry point. */
        fun create(onShake: () -> Unit): ShakeDetector = ShakeDetector(onShake)

        /**
         * Convert a sensor timestamp in nanoseconds (the unit SensorEvent timestamps
         * use) to the milliseconds the detector's samples carry.
         */
        fun toMillis(nanoseconds: Long): Long = nanoseconds / 1_000_000

        private const val FIRST_SAMPLE_DT_MS = 5L

        /** The filtered (high-pass) magnitude threshold, in g's, that counts as a
         * positive-going crossing. */
        const val THRESHOLD_G = 2.0

        /** Number of positive-high crossings required inside the window to declare a shake. */
        const val CROSSING_COUNT = 3

        /**
         * Window, in ms: crossing timestamps must fall within this span (a span of exactly
         * 500 ms still counts).
         */
        const val WINDOW_MS = 500L

        /** Cooldown, in ms, after firing during which further samples do not re-fire. */
        const val COOLDOWN_MS = 2000L

        /** Cutoff frequency, in Hz, of the one-pole high-pass on the magnitude. */
        const val CUTOFF_HZ = 2.0

        /** Gap, in ms, beyond which the detector resets every detection field. */
        const val LARGE_GAP_MS = 1000L
    }
}
