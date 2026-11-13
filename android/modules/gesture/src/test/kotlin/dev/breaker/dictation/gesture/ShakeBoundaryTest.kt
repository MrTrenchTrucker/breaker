// android/modules/gesture - ShakeBoundaryTest
// Card: android/modules/gesture/AGENTS.md   Registry: modules.toml [module.android_gesture]
// Owns: the exact-value and boundary tests of ShakeDetector and ShakeHandle that ShakeDetectorTest does not pin.
// Depends on: JUnit 4; no Android types (a plain JVM test reads it).

package dev.breaker.dictation.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Boundary and exact-value tests found by a mutant sweep: each test says which edit of the
 * detector it turns red. Synthetic samples only; fire counts and the filter's own AC term are
 * asserted, with the expected AC term computed here from the card's formula.
 */
class ShakeBoundaryTest {

    private val g = 9.81

    private fun sample(magG: Double, t: Long): ShakeDetector.Sample =
        ShakeDetector.Sample((magG * g).toFloat(), 0f, 0f, t)

    private fun restRun(ms: Double, hz: Int, t0: Double, magG: Double = 1.2): List<ShakeDetector.Sample> {
        val dt = 1.0 / hz * 1000.0
        return (0 until maxOf(Math.ceil(ms / dt).toInt(), 1)).map { i ->
            sample(magG, Math.floor(t0 + i * dt + 0.5).toLong())
        }
    }

    private fun spike(t: Double, hz: Int): List<ShakeDetector.Sample> {
        val dt = 1.0 / hz * 1000.0
        return (0 until 3).map { i -> sample(4.5, Math.floor(t + i * dt + 0.5).toLong()) }
    }

    /** Rest, then three sharp spikes [spacing] ms apart at 200 Hz, then rest: one shake. */
    private fun burst(spacing: Double, t0: Double): List<ShakeDetector.Sample> {
        val out = ArrayList<ShakeDetector.Sample>()
        out += restRun(50.0, 200, t0)
        var t = t0 + 50.0
        for (k in 0 until 3) {
            if (k != 0) out += restRun(spacing - 15.0, 200, t - spacing + 15.0)
            out += spike(t, 200)
            t += spacing
        }
        out += restRun(150.0, 200, t - spacing + 15.0)
        return out
    }

    private fun countFires(samples: List<ShakeDetector.Sample>): Int {
        var count = 0
        val det = ShakeDetector.create { count++ }
        det.start()
        for (s in samples) det.feed(s)
        return count
    }

    /** The filter gain for a gap of [dtMs] ms, from the card: alpha = dt / (T + dt), T = 1 / (2 pi 2 Hz). */
    private fun alpha(dtMs: Long): Double {
        val dt = dtMs / 1000.0
        val t = 1.0 / (2.0 * PI * 2.0)
        return dt / (t + dt)
    }

    // ---- The exact AC term of the first sample ----

    @Test fun first_sample_is_seeded_with_a_five_ms_step() {
        val det = ShakeDetector.create { }
        det.start()
        det.feed(sample(2.0, 1000L))
        val lp = 1.0 + alpha(5L) * (2.0 - 1.0)
        assertEquals("gesture: the first sample moves the low-pass term by the 5 ms gain", 2.0 - lp, det.lastAcTerm, 1e-4)
    }

    @Test fun a_finite_sample_above_the_cap_is_held_at_the_cap() {
        val det = ShakeDetector.create { }
        det.start()
        det.feed(sample(3.35, 1000L))
        val lp = 1.0 + alpha(5L) * (3.3 - 1.0)
        assertEquals("gesture: a 3.35 g sample is held at 3.3 g", 3.3 - lp, det.lastAcTerm, 1e-4)
    }

    @Test fun a_finite_sample_below_the_cap_is_not_held() {
        val det = ShakeDetector.create { }
        det.start()
        det.feed(sample(3.0, 1000L))
        val lp = 1.0 + alpha(5L) * (3.0 - 1.0)
        assertEquals("gesture: a 3.0 g sample is used as it is", 3.0 - lp, det.lastAcTerm, 1e-4)
    }

    @Test fun a_gap_keeps_the_low_pass_term() {
        val det = ShakeDetector.create { }
        det.start()
        val settle = restRun(1500.0, 200, 0.0)
        for (s in settle) det.feed(s)
        val last = settle.last().timestampMs
        det.feed(sample(1.2, last + 1500L))
        det.feed(sample(1.2, last + 1505L))
        assertEquals("gesture: after a gap the term still sits at the rest value, so a rest sample has no AC part", 0.0, det.lastAcTerm, 1e-3)
    }

    // ---- start and stop ----

    @Test fun samples_after_stop_are_ignored() {
        var count = 0
        val det = ShakeDetector.create { count++ }
        det.start()
        det.stop()
        for (s in burst(150.0, 0.0)) det.feed(s)
        assertEquals("gesture: a stopped detector ignores a whole shake", 0, count)
    }

    // ---- timestamps and the gap rule ----

    @Test fun equal_timestamps_are_ignored() {
        val out = ArrayList<ShakeDetector.Sample>()
        out += restRun(1000.0, 200, 0.0)
        var last = out.last().timestampMs
        for (t in listOf(1000L, 1100L, 1200L)) {
            if (t - last > 5L) out += restRun((t - last - 5L).toDouble(), 200, (last + 5L).toDouble())
            out += sample(1.2, t)
            out += sample(4.5, t)
            last = t
        }
        out += restRun(200.0, 200, (last + 5L).toDouble())
        assertEquals("gesture: a spike that repeats the timestamp of the sample before it never counts", 0, countFires(out))
    }

    private fun burstAfterGap(gapMs: Long): List<ShakeDetector.Sample> {
        val first = burst(150.0, 0.0)
        return first + burst(150.0, (first.last().timestampMs + gapMs).toDouble())
    }

    @Test fun the_cooldown_survives_a_gap_of_999_ms() {
        assertEquals("gesture: 999 ms is not a large gap", 1, countFires(burstAfterGap(999L)))
    }

    @Test fun the_cooldown_survives_a_gap_of_exactly_1000_ms() {
        assertEquals("gesture: exactly 1000 ms is not a large gap", 1, countFires(burstAfterGap(1000L)))
    }

    @Test fun a_gap_of_1001_ms_clears_the_cooldown() {
        assertEquals("gesture: 1001 ms is a large gap and clears the cooldown", 2, countFires(burstAfterGap(1001L)))
    }

    // ---- the cooldown ----

    @Test fun a_second_burst_inside_the_cooldown_is_suppressed_without_a_gap() {
        val out = burst(150.0, 0.0) + restRun(985.0, 200, 515.0) + burst(150.0, 1500.0)
        assertEquals("gesture: a burst 1.1 s after a shake is inside the 2 s cooldown", 1, countFires(out))
    }

    @Test fun a_crossing_at_the_very_end_of_the_cooldown_counts() {
        val out = ArrayList<ShakeDetector.Sample>()
        out += burst(150.0, 0.0)
        out += restRun(1835.0, 200, 515.0)
        out += spike(2350.0, 200)
        out += restRun(135.0, 200, 2365.0)
        out += spike(2500.0, 200)
        out += restRun(135.0, 200, 2515.0)
        out += spike(2650.0, 200)
        out += restRun(150.0, 200, 2665.0)
        val untilFirstEnd = out.filter { it.timestampMs <= 2600L }
        assertEquals("gesture: the cooldown ends at 2350 ms, so the crossing at 2350 ms is the first of the next shake", 2, countFires(untilFirstEnd))
    }

    // ---- the crossing threshold ----

    /** One-sample pulses of [magG] g on a 1.0 g rest at 1000, 1240 and 1480 ms. */
    private fun pulses(magG: Double): List<ShakeDetector.Sample> {
        val out = ArrayList<ShakeDetector.Sample>()
        out += restRun(1000.0, 200, 0.0, 1.0)
        var last = out.last().timestampMs
        for ((k, t) in listOf(1000L, 1240L, 1480L).withIndex()) {
            if (k > 0) out += restRun((t - last - 5L).toDouble(), 200, (last + 5L).toDouble(), 1.0)
            out += sample(magG, t)
            last = t
        }
        out += restRun(200.0, 200, (last + 5L).toDouble(), 1.0)
        return out
    }

    @Test fun pulses_with_an_ac_term_just_under_the_threshold_never_fire() {
        assertEquals("gesture: an AC term of about 0.645 g is under the 0.65 g threshold", 0, countFires(pulses(1.6882)))
    }

    @Test fun pulses_with_an_ac_term_just_over_the_threshold_fire_once() {
        assertEquals("gesture: an AC term of about 0.652 g is over the 0.65 g threshold", 1, countFires(pulses(1.6956)))
    }

    // ---- the handle ----

    private class CountingPort : ShakePort {
        var starts = 0
        override fun start(onSample: (Float, Float, Float, Long) -> Unit): Boolean {
            starts++
            return true
        }

        override fun stop() = Unit
    }

    @Test fun a_second_start_while_started_reports_true() {
        val port = CountingPort()
        val handle = ShakeHandle.create({ }, port)
        assertTrue("gesture: the first start reports true", handle.start())
        assertTrue("gesture: a second start while started reports true", handle.start())
        assertEquals("gesture: the port is registered once", 1, port.starts)
    }
}
