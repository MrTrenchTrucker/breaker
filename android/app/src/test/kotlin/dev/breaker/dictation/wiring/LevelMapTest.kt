package dev.breaker.dictation.wiring

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

class LevelMapTest {

    /** A frame of [count] samples at one amplitude, so its RMS is that amplitude. */
    private fun flatFrame(amplitude: Float, count: Int = 320): FloatArray = FloatArray(count) { amplitude }

    /** The level of a frame, taken the way the meter takes it: its dBFS from the sum of squares, then the bar. */
    private fun frameLevel(samples: FloatArray): Float {
        val sumSquares = LevelMap.sumSquaresOf(samples) ?: return 0f
        return LevelMap.levelOf(LevelMap.dbfsOf(sumSquares, samples.size))
    }

    /** The level of a flat frame whose RMS is [dbfs] dBFS. */
    private fun levelAtDbfs(dbfs: Double): Float = frameLevel(flatFrame(10.0.pow(dbfs / 20.0).toFloat()))

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
        assertEquals("app: half a frame at minus 33 dBFS must read as the RMS, about 0.444", 24f / 54f, frameLevel(half), 1e-3f)
    }

    @Test
    fun `a NaN sample reads as 0`() {
        assertEquals("app: a NaN sample must read as 0", 0f, frameLevel(floatArrayOf(0.5f, Float.NaN)), 0f)
    }

    @Test
    fun `an infinite sample reads as 0`() {
        assertEquals("app: an infinite sample must read as 0", 0f, frameLevel(floatArrayOf(Float.POSITIVE_INFINITY)), 0f)
    }

    @Test
    fun `an empty frame reads as 0`() {
        assertEquals("app: an empty frame must read as 0", 0f, frameLevel(FloatArray(0)), 0f)
    }

    @Test
    fun `a silent frame reads as 0`() {
        assertEquals("app: a silent frame must read as 0", 0f, frameLevel(FloatArray(320)), 0f)
    }

    @Test
    fun `a zero point above minus 60 moves the bottom of the bar up`() {
        assertEquals("app: minus 30 dBFS over a zero point of minus 42 must read 0.3333", 0.3333f, LevelMap.levelOf(-30f, -42f), 1e-4f)
        assertEquals("app: the zero point itself must read as 0", 0f, LevelMap.levelOf(-42f, -42f), 0f)
    }

    @Test
    fun `a missing dBFS gives 0 at any zero point`() {
        assertEquals("app: no information must read as 0", 0f, LevelMap.levelOf(null, -42f), 0f)
    }
}
