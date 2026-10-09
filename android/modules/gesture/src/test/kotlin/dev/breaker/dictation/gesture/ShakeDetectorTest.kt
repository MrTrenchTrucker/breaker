// android/modules/gesture - ShakeDetectorTest
// Card: android/modules/gesture/AGENTS.md   Registry: modules.toml [module.android_gesture]
// Owns: the deterministic JUnit proof of ShakeDetector's fire counts.
// Depends on: JUnit 4; no Android types (a plain JVM test reads it).

package dev.breaker.dictation.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Deterministic JUnit proof for [ShakeDetector]: synthetic samples only, a fresh detector
 * per test, exact fire counts asserted. No clock waits, no order or core-count dependence.
 *
 * Test method names are the contract: they map 1:1 to the registry's `expected_red` ids
 * exactly (that file is the authority). Fire counts are the pinned behaviour of the
 * detector; each input is a fixed deterministic signal with a known expected count.
 */
class ShakeDetectorTest {

    private val G = 9.81 // gravity in m/s^2
    private val BASELINE_G = 1.2 // steady-state magnitude in g
    private val SPIKE_G = 4.5 // peak amplitude of a hard jolt sample

    /** Count fires by feeding [samples] into a fresh detector, returning the fire count. */
    private fun countFires(samples: List<ShakeDetector.Sample>): Long {
        var count = 0L
        ShakeDetector.create { count++ }.let { det ->
            det.start()
            for (s in samples) det.feed(s)
        }
        return count
    }

    /** Feed [samples] into a fresh detector, asserting exactly [expected] fires. */
    private fun expectFires(samples: List<ShakeDetector.Sample>, expected: Int) {
        val got = countFires(samples)
        assertEquals("unexpected fire count", expected.toLong(), got)
    }

    // ---- Producers (fixed deterministic inputs) ----

    /** 450 ms of an 11 Hz, 3.5 g swing on a 1.2 g baseline (x axis).
     * At 3.0 g the corrected low-pass leaves the 50 Hz run one crossing short; 3.5 g is the smallest tested amplitude that fires once at both rates. The emitted x values are m/s^2 (g x 9.81), matching every other producer here. */
    private fun shake(rate_hz: Int, ms: Double = 450.0): List<ShakeDetector.Sample> {
        val dt = 1.0 / rate_hz
        val n = Math.ceil(ms / 1000.0 / dt).toInt()
        return (0 until n).map { i ->
            ShakeDetector.Sample(
                x = ((BASELINE_G + 3.5 * sin(2.0 * PI * 11.0 * i * dt)) * G).toFloat(),
                y = 0f,
                z = 0f,
                timestampMs = (Math.floor(i * dt * 1000 + 0.5)).toLong(),
            )
        }
    }

    /** A phone at rest: constant 1.2 g on x for [seconds] - the gravity-only signal. */
    private fun still_phone(rate_hz: Int, seconds: Double = 3.0): List<ShakeDetector.Sample> {
        val dt = 1.0 / rate_hz
        return (0 until Math.ceil(seconds / dt).toInt()).map { i ->
            ShakeDetector.Sample((BASELINE_G * G).toFloat(), 0f, 0f, (Math.floor(i * dt * 1000 + 0.5)).toLong())
        }
    }

    /** Gravity only: constant 1.2 g magnitude, vector rotated by [tilt]. */
    private fun rest(rate_hz: Int, seconds: Double = 1.5, tilt: Double = 0.0): List<ShakeDetector.Sample> {
        val dt = 1.0 / rate_hz
        val m = BASELINE_G * G
        return (0 until Math.ceil(seconds / dt).toInt()).map { i ->
            ShakeDetector.Sample((m * cos(tilt)).toFloat(), (m * sin(tilt)).toFloat(), 0f, (Math.floor(i * dt * 1000 + 0.5)).toLong())
        }
    }

    /** Gravity magnitude constant, direction drifting at 0.25 rad/s. */
    private fun slow_tilt(rate_hz: Int, seconds: Double = 2.0): List<ShakeDetector.Sample> {
        val dt = 1.0 / rate_hz
        val m = BASELINE_G * G
        return (0 until Math.ceil(seconds / dt).toInt()).map { i ->
            val t = i * dt * 1000.0
            ShakeDetector.Sample(
                x = (m * cos(0.25 * t / 1000.0)).toFloat(),
                y = (m * sin(0.25 * t / 1000.0)).toFloat(),
                z = 0f,
                timestampMs = (Math.floor(i * dt * 1000 + 0.5)).toLong(),
            )
        }
    }

    private fun rest_run(ms: Double, rate_hz: Int, t0: Double): List<ShakeDetector.Sample> {
        val dt = 1.0 / rate_hz * 1000.0
        return (0 until Math.max(Math.ceil(ms / dt).toInt(), 1)).map { i ->
            ShakeDetector.Sample((BASELINE_G * G).toFloat(), 0f, 0f, (Math.floor(t0 + i * dt + 0.5)).toLong())
        }
    }

    private fun spike(t_ms: Double, rate_hz: Int): List<ShakeDetector.Sample> {
        val dt = 1.0 / rate_hz * 1000.0
        return (0 until 3).map { i -> ShakeDetector.Sample((SPIKE_G * G).toFloat(), 0f, 0f, (Math.floor(t_ms + i * dt + 0.5)).toLong()) }
    }

    /** rest + `count` sharp 3-sample spikes, first spike at t0 + 50 ms. */
    private fun burst_crossings(count: Int, spacing_ms: Double, rate_hz: Int, t0: Double = 0.0): List<ShakeDetector.Sample> {
        val out = ArrayList<ShakeDetector.Sample>()
        out += rest_run(50.0, rate_hz, t0)
        var t = t0 + 50.0
        for (k in 0 until count) {
            if (k != 0) out += rest_run(spacing_ms - 15.0, rate_hz, t - spacing_ms + 15.0)
            out += spike(t, rate_hz)
            t += spacing_ms
        }
        out += rest_run(150.0, rate_hz, t - spacing_ms + 15.0)
        return out
    }

    // ---- Baseline battery (fixed inputs, pinned fire counts) ----

    @Test fun shake_50hz_fires_once() = expectFires(shake(50), 1)
    @Test fun shake_200hz_fires_once() = expectFires(shake(200), 1)
    @Test fun rest_50hz_never_fires() = expectFires(rest(50), 0)
    @Test fun rest_200hz_never_fires() = expectFires(rest(200), 0)
    @Test fun tilted_rest_never_fires() = expectFires(rest(200, tilt = PI / 3), 0)
    @Test fun slow_tilt_never_fires() = expectFires(slow_tilt(50), 0)
    @Test fun slow_tilt_200_never_fires() = expectFires(slow_tilt(200), 0)
    @Test fun still_phone_never_fires_and_ac_settles() {
        var count = 0L
        val det = ShakeDetector.create { count++ }
        det.start()
        still_phone(200, 3.0).forEach { det.feed(it) }
        assertEquals("a phone at rest must never shake", 0L, count)
        assertTrue(
            "the AC term of a resting phone must settle toward zero (gravity removed); " +
                "a term that stays high means the filter is not removing the DC component",
            det.lastAcTerm < 0.05,
        )
    }
    @Test fun crossings_over_520ms_do_not_fire() = expectFires(burst_crossings(3, 260.0, 200), 0)
    @Test fun crossings_over_600ms_do_not_fire() = expectFires(burst_crossings(3, 300.0, 200), 0)
    @Test fun crossings_within_300ms_fire() = expectFires(burst_crossings(3, 150.0, 200), 1)
    @Test fun second_burst_in_cooldown_suppressed() =
        expectFires(listOf(burst_crossings(3, 150.0, 200), burst_crossings(3, 150.0, 200, t0 = 600.0)).flatMap { it }, 1)
    @Test fun cooldown_expires_and_fires_again() =
        expectFires(listOf(burst_crossings(3, 150.0, 200), rest_run(1780.0, 200, 515.0), burst_crossings(3, 150.0, 200, t0 = 2300.0)).flatMap { it }, 2)
    @Test fun gap_then_burst_fires() =
        expectFires(listOf(burst_crossings(3, 150.0, 200), rest_run(1980.0, 200, 515.0), burst_crossings(3, 150.0, 200, t0 = 2500.0)).flatMap { it }, 2)
    @Test fun same_timestamp_sample_ignored() = expectFires(same_t(), 0)
    @Test fun non_increasing_timestamp_ignored() = expectFires(dup_burst(3, 150.0, 200), 1)

    // ---- Additional discriminator tests (remaining sample producers removed) ----

    /** The filter canary: 3 Hz cosine on x, AC amplitude [amp_g] g, lasts [seconds]. */
    private fun canary(rate_hz: Int, amp_g: Double, seconds: Double = 2.0): List<ShakeDetector.Sample> {
        val dt = 1.0 / rate_hz
        return (0 until Math.ceil(seconds / dt).toInt()).map { i ->
            ShakeDetector.Sample((amp_g * G * cos(2 * PI * 3.0 * i * dt)).toFloat(), 0f, 0f, (Math.floor(i * dt * 1000 + 0.5)).toLong())
        }
    }

    @Test fun light_vibration_never_fires() = expectFires(canary(50, 2.1), 0)
    @Test fun rate_invariance_50hz() = expectFires(shake(50), 1)
    @Test fun rate_invariance_200hz() = expectFires(shake(200), 1)
    @Test fun gap_reset_then_burst_fires() =
        expectFires(listOf(burst_crossings(2, 150.0, 200), gap_samples(), burst_crossings(3, 150.0, 200, t0 = 2495.0)).flatMap { it }, 1)

    @Test fun out_of_order_spike_is_ignored() {
        val base = two_spikes()
        val last = base.last()
        val late = ShakeDetector.Sample((SPIKE_G * G).toFloat(), 0f, 0f, last.timestampMs - 1)
        expectFires(base + listOf(late), 0)
    }

    @Test fun large_gap_clears_the_cooldown() =
        expectFires(
            listOf(burst_crossings(3, 150.0, 200), rest_run(100.0, 200, 1600.0), burst_crossings(3, 150.0, 200, t0 = 1700.0)).flatMap { it },
            2,
        )

    @Test fun ac_settles_within_one_second_at_50hz() = assertAcSettles(50)

    @Test fun ac_settles_within_one_second_at_200hz() = assertAcSettles(200)

    private fun assertAcSettles(rateHz: Int) {
        val det = ShakeDetector.create { }
        det.start()
        still_phone(rateHz, 1.0).forEach { det.feed(it) }
        assertTrue("the AC term at $rateHz Hz must settle within one second, got ${det.lastAcTerm}", det.lastAcTerm < 0.05)
    }

    // ---- Handle tests (fake port; proves start/stop wiring end to end) ----

    /** Recording fake: counts register/unregister calls, replays samples on demand.
     * `allowStart` (default true) drives the refused-start path. */
    private class FakeShakePort : ShakePort {
        var startCalls = 0
        var stopCalls = 0
        var allowStart = true
        var lastOnSample: ((Float, Float, Float, Long) -> Unit)? = null
        override fun start(onSample: (Float, Float, Float, Long) -> Unit): Boolean {
            startCalls++
            lastOnSample = onSample
            return allowStart
        }
        override fun stop() {
            stopCalls++
            lastOnSample = null
        }
    }

    @Test fun handle_start_registers_port_once() {
        val port = FakeShakePort()
        var fired = 0
        val h = ShakeHandle.create({ fired++ }, port)
        h.start()
        port.lastOnSample!!(1.0f, 0f, 0f, 0L)
        assertEquals("port must register its listener once", 1, port.startCalls)
        assertEquals("no stop before start", 0, port.stopCalls)
    }

    @Test fun handle_start_twice_registers_once() {
        val port = FakeShakePort()
        val h = ShakeHandle.create({ /* fired++ */ }, port)
        h.start()
        h.start()
        assertEquals("start must be idempotent", 1, port.startCalls)
    }

    @Test fun handle_stop_removes_listener() {
        val port = FakeShakePort()
        val h = ShakeHandle.create({ /* fired++ */ }, port)
        h.start()
        h.stop()
        assertEquals("stop must unregister", 1, port.stopCalls)
        assertEquals("start called exactly once", 1, port.startCalls)
    }

    @Test fun handle_stop_before_start_is_safe() {
        val port = FakeShakePort()
        val h = ShakeHandle.create({ /* fired++ */ }, port)
        h.stop() // safe before any start
        h.start()
        assertEquals("pre-start stop is a no-op", 1, port.startCalls)
        assertEquals("pre-start stop must not register", 0, port.stopCalls)
    }

    @Test fun handle_restart_after_stop() {
        val port = FakeShakePort()
        var fired = 0
        val h = ShakeHandle.create({ fired++ }, port)
        h.start()
        h.stop()
        h.start()
        assertEquals("a start after a stop must register again", 2, port.startCalls)
        for (s in shake(50)) port.lastOnSample!!(s.x, s.y, s.z, s.timestampMs)
        assertEquals("the restarted handle must detect one shake", 1, fired)
    }

    @Test fun handle_detects_a_shake_from_port_samples() {
        val port = FakeShakePort()
        var fired = 0
        val h = ShakeHandle.create({ fired++ }, port)
        h.start()
        for (s in shake(50)) port.lastOnSample!!(s.x, s.y, s.z, s.timestampMs)
        assertEquals("the handle must detect one shake through the port", 1, fired)
    }

    @Test fun to_millis_turns_50hz_nanosecond_timestamps_into_a_20ms_step() {
        val t0 = 1_000_000_000L  // 1 s, in nanoseconds
        for (i in 0..4) {
            // 50 Hz: one sample every 20 ms = 20,000,000 ns
            assertEquals(1000L + i * 20, ShakeDetector.toMillis(t0 + i * 20_000_000L))
        }
    }

    @Test fun handle_shake_fires_once_with_helper_converted_timestamps() {
        val port = FakeShakePort()
        var fired = 0
        val h = ShakeHandle.create({ fired++ }, port)
        assertTrue(h.start())
        for (s in shake(50)) {
            // the timestamps the adapter would deliver: the sample's own timestamp,
            // expressed in nanoseconds and converted back through the same helper
            port.lastOnSample!!(s.x, s.y, s.z, ShakeDetector.toMillis(s.timestampMs * 1_000_000L))
        }
        assertEquals("a shake whose timestamps pass through the helper must fire once", 1, fired)
    }

    @Test fun handle_refused_start_keeps_the_detector_stopped_and_retries() {
        val port = FakeShakePort()
        port.allowStart = false
        var fired = 0
        val h = ShakeHandle.create({ fired++ }, port)
        assertFalse("a refused start must report false", h.start())
        for (s in shake(50)) port.lastOnSample!!(s.x, s.y, s.z, s.timestampMs)
        assertEquals("the detector stays stopped until a start succeeds", 0, fired)
        port.allowStart = true
        assertTrue("a later start on a working port must report true", h.start())
        for (s in shake(50)) port.lastOnSample!!(s.x, s.y, s.z, s.timestampMs)
        assertEquals("the retried start must detect one shake", 1, fired)
    }

    // ---- Helpers assembling compound cases from the producers above ----

    private fun same_t(): List<ShakeDetector.Sample> {
        val base = two_spikes()
        return base + listOf(ShakeDetector.Sample(base.last().x, base.last().y, base.last().z, base.last().timestampMs))
    }

    private fun dup_burst(count: Int, spacing_ms: Double, rate_hz: Int): List<ShakeDetector.Sample> {
        val b = burst_crossings(3, 150.0, 200)
        return b.take(8) + listOf(b[8], b[8], b[8]) + b.drop(8)
    }

    private fun gap_samples(): List<ShakeDetector.Sample> {
        // two-spike burst, then a short 3-sample spike tail 16 ms after the last sample (no
        // gap reset here); the test appends the next burst ~1100 ms later, which is the large
        // gap that resets the detector state.
        val base = two_spikes(200, t0 = 1000.0)
        val firstSpkEnd = base.last().timestampMs + 16
        return (0 until 3).map { i -> ShakeDetector.Sample((SPIKE_G * G).toFloat(), 0f, 0f, (firstSpkEnd + i * 5).toLong()) }
    }

    private fun two_spikes(rate_hz: Int = 200, t0: Double = 1000.0, spacing_ms: Double = 150.0): List<ShakeDetector.Sample> {
        val out = ArrayList<ShakeDetector.Sample>()
        out += rest_run(50.0, rate_hz, 0.0)
        out += rest_run(950.0, rate_hz, 50.0)
        out += spike(t0 - 50.0 + 50.0, rate_hz) // first spike at t0
        out += rest_run(spacing_ms - 15.0, rate_hz, t0 + 15.0)
        out += spike(t0 + spacing_ms, rate_hz) // second at t0 + spacing
        out += rest_run(200.0, rate_hz, t0 + spacing_ms + 15.0)
        return out
    }
}
