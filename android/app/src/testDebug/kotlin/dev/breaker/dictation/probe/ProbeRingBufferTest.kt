package dev.breaker.dictation.probe

import org.junit.Assert.assertEquals
import org.junit.Test

/** The probe ring buffer: 500 lines, synchronized, snapshot copy, clear. Pure Kotlin. */
internal class ProbeRingBufferTest {

    @Test
    fun `add keeps up to capacity lines and drops the oldest`() {
        val ring = ProbeRingBuffer(3)
        ring.add("one")
        ring.add("two")
        ring.add("three")
        ring.add("four")
        assertEquals("ring: capacity 3 keeps the last 3 lines", listOf("two", "three", "four"), ring.snapshot())
    }

    @Test
    fun `snapshot returns oldest first`() {
        val ring = ProbeRingBuffer(500)
        ring.add("first")
        ring.add("second")
        assertEquals("ring: snapshot is oldest first", listOf("first", "second"), ring.snapshot())
    }

    @Test
    fun `clear empties the ring`() {
        val ring = ProbeRingBuffer(500)
        ring.add("one")
        ring.add("two")
        ring.clear()
        assertEquals("ring: clear leaves no lines", emptyList<String>(), ring.snapshot())
    }

    @Test
    fun `snapshot is a copy later adds do not change it`() {
        val ring = ProbeRingBuffer(500)
        ring.add("one")
        val snap = ring.snapshot()
        ring.add("two")
        assertEquals("ring: snapshot is unchanged by later adds", listOf("one"), snap)
        assertEquals("ring: live snapshot grows", listOf("one", "two"), ring.snapshot())
    }

    @Test
    fun `the probe ring holds five hundred lines and drops the oldest at the next`() {
        probeRing.clear()
        for (number in 1..501) probeRing.add("line $number")
        val lines = probeRing.snapshot()
        assertEquals("ring: five hundred lines", 500, lines.size)
        assertEquals("ring: the oldest line is gone", "line 2", lines.first())
        assertEquals("ring: the newest line is last", "line 501", lines.last())
        probeRing.clear()
    }
}
