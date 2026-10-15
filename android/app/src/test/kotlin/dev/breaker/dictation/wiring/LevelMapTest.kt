package dev.breaker.dictation.wiring

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

class LevelMapTest {

    /** A frame of [count] samples at one amplitude, so its RMS is that amplitude. */
    private fun flatFrame(amplitude: Float, count: Int = 320): FloatArray = FloatArray(count) { amplitude }

    /** The level of a flat frame whose RMS is [dbfs] dBFS. */
    private fun levelAtDbfs(dbfs: Double): Float = LevelMap.levelOf(flatFrame(10.0.pow(dbfs / 20.0).toFloat()))

    @Test
    fun `minus 60 dBFS reads as the bottom of the bar`() {
        assertEquals("app: the floor must read as 0", 0f, levelAtDbfs(-60.0), 1e-4f)
    }

    @Test
    fun `minus 6 dBFS reads as the top of the bar`() {
        assertEquals("app: the ceiling must read as 1", 1f, levelAtDbfs(-6.0), 1e-4f)
    }

    @Test
    fun `minus 33 dBFS reads halfway up the bar`() {
        assertEquals("app: the middle of the range must read as 0.5", 0.5f, levelAtDbfs(-33.0), 1e-4f)
    }

    @Test
    fun `a level below the floor is held at 0`() {
        assertEquals("app: a level below the floor must clamp to 0", 0f, levelAtDbfs(-80.0), 0f)
    }

    @Test
    fun `a level above the ceiling is held at 1`() {
        assertEquals("app: a level above the ceiling must clamp to 1", 1f, levelAtDbfs(0.0), 0f)
    }

    @Test
    fun `the level is the RMS of the whole frame and not its peak`() {
        val half = FloatArray(640) { index -> if (index < 320) 10.0.pow(-33.0 / 20.0).toFloat() else 0f }
        assertEquals("app: half a frame at minus 33 dBFS must read as the RMS, about 0.444", 24f / 54f, LevelMap.levelOf(half), 1e-3f)
    }

    @Test
    fun `a NaN sample reads as 0`() {
        assertEquals("app: a NaN sample must read as 0", 0f, LevelMap.levelOf(floatArrayOf(0.5f, Float.NaN)), 0f)
    }

    @Test
    fun `an infinite sample reads as 0`() {
        assertEquals("app: an infinite sample must read as 0", 0f, LevelMap.levelOf(floatArrayOf(Float.POSITIVE_INFINITY)), 0f)
    }

    @Test
    fun `an empty frame reads as 0`() {
        assertEquals("app: an empty frame must read as 0", 0f, LevelMap.levelOf(FloatArray(0)), 0f)
    }

    @Test
    fun `a silent frame reads as 0`() {
        assertEquals("app: a silent frame must read as 0", 0f, LevelMap.levelOf(FloatArray(320)), 0f)
    }
}
