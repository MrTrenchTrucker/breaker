package dev.breaker.dictation.wiring

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LevelMeterTest {

    /** A frame of [count] samples at one amplitude. */
    private fun flat(amplitude: Float, count: Int): FloatArray = FloatArray(count) { amplitude }

    /** An amplitude whose level is halfway up the bar (minus 33 dBFS). */
    private val halfway: Float = 10.0.pow(-33.0 / 20.0).toFloat()

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
    fun `the posted level is the level of the latest frame in the window`() {
        val meter = LevelMeter()
        assertNull("app: a loud frame alone must not show a level", meter.accept(flat(1f, 320)))
        val shown = meter.accept(flat(halfway, 747))
        assertNotNull("app: the window must fill on the quiet frame", shown)
        assertEquals("app: the shown level must be the quiet latest frame, not the loud one", 0.5f, shown!!, 1e-3f)
    }

    @Test
    fun `a level rises to its new value at once`() {
        val meter = LevelMeter()
        assertEquals("app: a loud window must rise from 0 to 1 at one update", 1f, meter.accept(flat(1f, 1067))!!, 1e-6f)
    }

    @Test
    fun `a silent input falls by one twelfth per update`() {
        val meter = LevelMeter()
        meter.accept(flat(1f, 1067))
        assertEquals("app: the first silent update must fall to 11/12", 11f / 12f, meter.accept(flat(0f, 1067))!!, 1e-6f)
        assertEquals("app: the second silent update must fall to 10/12", 10f / 12f, meter.accept(flat(0f, 1067))!!, 1e-6f)
    }

    @Test
    fun `a fresh meter starts from zero`() {
        val first = LevelMeter()
        assertEquals("app: the earlier take must reach 1", 1f, first.accept(flat(1f, 1067))!!, 1e-6f)
        val second = LevelMeter()
        assertEquals(
            "app: a new meter must show the unsmoothed level of its first window",
            0.5f,
            second.accept(flat(halfway, 1067))!!,
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
}
