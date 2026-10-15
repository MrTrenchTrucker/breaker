package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseFloorTest {

    /** The sample count of one update, the cadence of the meter. */
    private val step: Int = FloorTuning.STEP_SAMPLES

    /** Takes [count] updates of the same window level. */
    private fun feed(floor: NoiseFloor, dbfs: Float?, count: Int) {
        repeat(count) { floor.update(dbfs, step) }
    }

    @Test
    fun `the seed is the lower median of the window levels so far`() {
        val floor = NoiseFloor()
        floor.update(-40f, step)
        floor.update(-50f, step)
        floor.update(-30f, step)
        assertEquals("app: after three windows the seed must be the middle level, -40", -40f, floor.floorDbfs!!, 1e-4f)
        floor.update(-45f, step)
        assertEquals("app: after four windows the seed must be the lower middle level, -45", -45f, floor.floorDbfs!!, 1e-4f)
    }

    @Test
    fun `no rise limit and no fall limit while the seed lasts`() {
        val floor = NoiseFloor()
        floor.update(-20f, step)
        floor.update(-58f, step)
        assertEquals("app: the seed must fall 38 dB at once, to -58", -58f, floor.floorDbfs!!, 1e-4f)
        floor.update(-20f, step)
        assertEquals("app: the seed must rise 38 dB at once, back to -20", -20f, floor.floorDbfs!!, 1e-4f)
    }

    @Test
    fun `after the seed the floor rises by at most about one fifteenth of a dB per update`() {
        val floor = NoiseFloor()
        feed(floor, -50f, 8)
        assertEquals("app: the seed must end at -50", -50f, floor.floorDbfs!!, 1e-4f)
        floor.update(-10f, step)
        assertEquals("app: a window 40 dB above the floor must lift it one rise step, to -49.9333", -49.9333f, floor.floorDbfs!!, 1e-3f)
    }

    @Test
    fun `after the seed the floor falls by at most about 2 dB per update`() {
        val floor = NoiseFloor()
        feed(floor, -30f, 8)
        assertEquals("app: the seed must end at -30", -30f, floor.floorDbfs!!, 1e-4f)
        floor.update(-60f, step)
        assertEquals("app: a window 30 dB below the floor must pull it one fall step, to -32.0006", -32.0006f, floor.floorDbfs!!, 1e-3f)
        floor.update(-32.5f, step)
        assertEquals("app: a window 0.5 dB below the floor is not held back, so the floor is -32.5", -32.5f, floor.floorDbfs!!, 1e-4f)
    }

    @Test
    fun `the floor is never below minus 60`() {
        val floor = NoiseFloor()
        feed(floor, -80f, 8)
        assertEquals("app: a floor of minus 80 must be held at minus 60", -60f, floor.floorDbfs!!, 1e-4f)
        assertEquals("app: the zero point of a floor at minus 60 must be minus 52", -52f, floor.zeroPointDbfs!!, 1e-4f)
    }

    @Test
    fun `the zero point is capped at minus 18`() {
        val quiet = NoiseFloor()
        quiet.update(-20f, step)
        assertEquals("app: a floor of minus 20 must give a zero point of minus 18, not minus 12", -18f, quiet.zeroPointDbfs!!, 1e-4f)
        val louder = NoiseFloor()
        louder.update(-30f, step)
        assertEquals("app: a floor of minus 30 must give a zero point of minus 22", -22f, louder.zeroPointDbfs!!, 1e-4f)
    }

    @Test
    fun `silence before the first level does not seed the floor`() {
        val floor = NoiseFloor()
        floor.update(null, step)
        floor.update(null, step)
        assertNull("app: silence must leave the floor unset", floor.floorDbfs)
        assertNull("app: silence must leave the zero point unset", floor.zeroPointDbfs)
        floor.update(-40f, step)
        assertEquals("app: the first level after silence must seed the floor at -40", -40f, floor.floorDbfs!!, 1e-4f)
    }

    @Test
    fun `silence advances the seed clock`() {
        val floor = NoiseFloor()
        floor.update(-40f, step)
        feed(floor, null, 7)
        floor.update(-30f, step)
        assertEquals("app: once the count has passed the seed, -30 must move the floor one rise step, to -39.9333", -39.9333f, floor.floorDbfs!!, 1e-3f)
    }

    @Test
    fun `a new floor has no value`() {
        val floor = NoiseFloor()
        assertNull("app: a new floor must have no floor value", floor.floorDbfs)
        assertNull("app: a new floor must have no zero point", floor.zeroPointDbfs)
    }

    @Test
    fun `a floor first set after the seed takes the level at once`() {
        val floor = NoiseFloor()
        feed(floor, null, 8)
        assertNull("app: silence for 8536 samples must leave the floor unset", floor.floorDbfs)
        floor.update(-40f, step)
        assertEquals("app: the first level after the seed must set the floor to -40 at once", -40f, floor.floorDbfs!!, 1e-4f)
    }
}
