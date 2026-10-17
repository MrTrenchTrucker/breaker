package dev.breaker.dictation.wiring

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LevelMeterTest {

    /** A frame of [count] samples at one amplitude. */
    private fun flat(amplitude: Float, count: Int): FloatArray = FloatArray(count) { amplitude }

    /** The amplitude of a flat frame whose level is [dbfs] dBFS. */
    private fun amp(dbfs: Double): Float = 10.0.pow(dbfs / 20.0).toFloat()

    /** Eight separate frames of 1067 samples at [dbfs]: each is one update, and the eighth ends the seed. */
    private fun lead(meter: LevelMeter, dbfs: Double = -50.0) {
        repeat(8) { meter.accept(flat(amp(dbfs), 1067)) }
    }

    @Test
    fun `no level is shown until a window of 1067 samples has filled`() {
        val meter = LevelMeter()
        assertNull("app: the first 320 samples must not show a level", meter.accept(flat(1f, 320)))
        assertNull("app: 640 samples must not show a level", meter.accept(flat(1f, 320)))
        assertNull("app: 960 samples must not show a level", meter.accept(flat(1f, 320)))
        assertNotNull("app: 1280 samples must show a level", meter.accept(flat(1f, 320)))
    }

    @Test
    fun `exactly 1067 samples fill a window and 1066 do not`() {
        val almost = LevelMeter()
        assertNull("app: 1066 samples must not fill a window", almost.accept(flat(1f, 1066)))
        assertNotNull("app: one more sample after 1066 must fill the window", almost.accept(flat(1f, 1)))
        val exact = LevelMeter()
        assertNotNull("app: 1067 samples must fill a window", exact.accept(flat(1f, 1067)))
    }

    @Test
    fun `the samples left after an update are carried into the next window`() {
        val meter = LevelMeter()
        val posted = (1..11).map { meter.accept(flat(1f, 320)) != null }
        assertEquals(
            "app: frames of 320 samples must post at frames 4, 7 and 11, which carries the remainder",
            listOf(false, false, false, true, false, false, true, false, false, false, true),
            posted,
        )
    }

    @Test
    fun `the posted level is the level of the whole window and not of its last frame`() {
        val meter = LevelMeter()
        lead(meter)
        assertNull("app: 320 samples alone must not show a level", meter.accept(flat(amp(-30.0), 320)))
        val shown = meter.accept(flat(amp(-40.0), 747))
        assertNotNull("app: the window must fill on the 747-sample frame", shown)
        assertEquals("app: the shown level must be the whole window's 0.2119, not the last frame's 0.0538", 0.2119f, shown!!, 1e-3f)
    }

    @Test
    fun `a level rises to its new value at once`() {
        val meter = LevelMeter()
        lead(meter)
        assertEquals("app: a loud window must rise from 0 to 1 at one update", 1f, meter.accept(flat(1f, 1067))!!, 1e-6f)
    }

    @Test
    fun `a silent input falls by one twelfth per update`() {
        val meter = LevelMeter()
        lead(meter)
        meter.accept(flat(1f, 1067))
        assertEquals("app: the first silent update must fall to 11/12", 11f / 12f, meter.accept(flat(0f, 1067))!!, 1e-6f)
        assertEquals("app: the second silent update must fall to 10/12", 10f / 12f, meter.accept(flat(0f, 1067))!!, 1e-6f)
    }

    @Test
    fun `a fresh meter starts from zero`() {
        val first = LevelMeter()
        lead(first)
        assertEquals("app: the earlier take must reach 1", 1f, first.accept(flat(1f, 1067))!!, 1e-6f)
        val second = LevelMeter()
        lead(second)
        assertEquals(
            "app: a new meter must show the unsmoothed level of its first window",
            0.2486f,
            second.accept(flat(amp(-33.0), 1067))!!,
            1e-3f,
        )
    }

    @Test
    fun `a new meter starts a new floor`() {
        val first = LevelMeter()
        lead(first, -50.0)
        val second = LevelMeter()
        lead(second, -30.0)
        assertEquals(
            "app: a new meter must start its own floor at minus 30, so minus 30 dBFS reads 0",
            0f,
            second.accept(flat(amp(-30.0), 1067))!!,
            1e-6f,
        )
    }

    @Test
    fun `a quiet route keeps normal speech near the fixed map's value`() {
        val meter = LevelMeter()
        lead(meter)
        val shown = meter.accept(flat(amp(-20.0), 1067))!!
        assertEquals("app: minus 20 dBFS speech over a minus 50 floor must read 0.6104", 0.6104f, shown, 1e-3f)
        assertEquals("app: normal speech must stay within 0.15 of the fixed map's 0.7407", 0.7407f, shown, 0.15f)
    }

    @Test
    fun `a frame with a non-finite sample adds nothing to the window level`() {
        val meter = LevelMeter()
        lead(meter)
        assertNull("app: a frame of NaN samples must not show a level alone", meter.accept(FloatArray(320) { Float.NaN }))
        val shown = meter.accept(flat(amp(-30.0), 747))
        assertNotNull("app: the 747 samples must fill the window that the NaN frame counted toward", shown)
        assertEquals("app: the level must be the 747-sample window at minus 30 dBFS, 0.3321", 0.3321f, shown!!, 1e-3f)
    }

    @Test
    fun `digital silence is no information and does not move the floor, and shows 0 after the lead-in`() {
        val meter = LevelMeter()
        lead(meter)
        assertEquals("app: a window of digital silence must show 0", 0f, meter.accept(flat(0f, 1067))!!, 0f)
        assertEquals("app: a window at minus 50 dBFS after silence must show 0", 0f, meter.accept(flat(amp(-50.0), 1067))!!, 0f)
        assertEquals(
            "app: a window at minus 40 dBFS must show 0.0538, which needs the floor still near minus 50",
            0.0538f,
            meter.accept(flat(amp(-40.0), 1067))!!,
            1e-3f,
        )
    }

    @Test
    fun `a long run of mixed frame sizes posts the whole number of windows in the total`() {
        val meter = LevelMeter()
        var total = 0
        var posts = 0
        for (index in 0 until 2_000) {
            val size = if (index % 2 == 0) 320 else 512
            total += size
            if (meter.accept(flat(0.1f, size)) != null) posts += 1
        }
        assertEquals(
            "app: the posts must equal the total divided by the window, so no samples are lost or doubled",
            total / LevelMeter.SAMPLES_PER_UPDATE,
            posts,
        )
    }

    @Test
    fun `a frame longer than two windows gives one update and keeps the remainder`() {
        val meter = LevelMeter()
        assertNotNull("app: a frame of 5000 samples must show one level", meter.accept(flat(1f, 5000)))
        assertNull("app: 732 carried samples plus 334 must not fill a window", meter.accept(flat(1f, 334)))
        assertNotNull("app: one more sample must fill exactly one window", meter.accept(flat(1f, 1)))
    }

    @Test
    fun `a 100-sample tail counts toward the window`() {
        val meter = LevelMeter()
        assertNull("app: 1000 samples must not show a level", meter.accept(flat(1f, 1000)))
        assertNotNull("app: the 100-sample tail must fill the window", meter.accept(flat(1f, 100)))
    }

    @Test
    fun `an empty frame returns null and does not change the count`() {
        val meter = LevelMeter()
        assertNull("app: 1000 samples must not show a level", meter.accept(flat(1f, 1000)))
        assertNull("app: an empty frame must return null", meter.accept(FloatArray(0)))
        assertNotNull("app: 67 more samples must fill the window, so the empty frame counted nothing", meter.accept(flat(1f, 67)))
    }

    @Test
    fun `the seed clock adds each window's samples once and not the running total`() {
        val meter = LevelMeter()
        val early = listOf(-30.0, -30.0, -30.0, -30.0, -50.0).map { meter.accept(flat(amp(it), 1067))!! }
        assertEquals("app: the first five windows must all show 0, so the sixth shows its raw level", listOf(0f, 0f, 0f, 0f, 0f), early)
        assertEquals(
            "app: the sixth window at minus 20 dBFS must read 0.125 over a seed median of minus 30 and a zero point of minus 22",
            0.125f,
            meter.accept(flat(amp(-20.0), 1067))!!,
            1e-3f,
        )
    }

    @Test
    fun `a silent stretch inside a window counts as part of the window`() {
        val meter = LevelMeter()
        lead(meter)
        assertNull("app: 320 silent samples alone must not show a level", meter.accept(FloatArray(320)))
        val shown = meter.accept(flat(amp(-30.0), 747))
        assertNotNull("app: the 747 samples must fill the window that the silence counted toward", shown)
        assertEquals("app: 747 samples at minus 30 dBFS in a window of 1067 must read 0.2890, not 0.3321", 0.2890f, shown!!, 1e-3f)
    }

    @Test
    fun `a long frame counts all of its samples toward the seed, which ends at 8000`() {
        val meter = LevelMeter()
        val early = listOf(-30.0, -30.0, -30.0, -30.0, -50.0).map { meter.accept(flat(amp(it), 1600))!! }
        assertEquals("app: the first five frames of 1600 samples must each show 0", listOf(0f, 0f, 0f, 0f, 0f), early)
        assertEquals(
            "app: the sixth frame at minus 20 dBFS must read 0.2194, after the seed has ended at 8000 samples",
            0.2194f,
            meter.accept(flat(amp(-20.0), 1600))!!,
            1e-3f,
        )
    }
}
