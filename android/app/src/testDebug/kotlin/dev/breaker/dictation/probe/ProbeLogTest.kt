package dev.breaker.dictation.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ProbeLogTest {

    @Test
    fun `the log drops the oldest line at the forty first`() {
        val log = ProbeLog()
        for (time in 1..41) {
            log.add(time.toLong(), "activity", time, true)
        }
        val lines: List<InsetLine> = log.lines()
        assertEquals("probe: the log must keep forty lines", ProbeLog.LIMIT, lines.size)
        assertEquals("probe: the oldest line must be the second one added", 2L, lines.first().time)
        assertEquals("probe: the newest line must be the forty first", 41L, lines.last().time)
    }

    @Test
    fun `the lines are a copy`() {
        val log = ProbeLog()
        log.add(1L, "activity", 10, true)
        val before: List<InsetLine> = log.lines()
        log.add(2L, "activity", 20, true)
        assertEquals("probe: a copy taken before an add must keep its size", 1, before.size)
        assertEquals("probe: the log itself must hold both lines", 2, log.lines().size)
    }

    @Test
    fun `latest answers the newest line of its source`() {
        val log = ProbeLog()
        log.add(1L, "activity", 10, true)
        log.add(2L, "overlay", 20, false)
        log.add(3L, "activity", 30, true)
        assertEquals("probe: latest activity must be the newest activity line", 30, log.latest("activity")?.bottom)
        assertEquals("probe: latest overlay must be the overlay line", 2L, log.latest("overlay")?.time)
        assertNull("probe: a source with no line must answer null", log.latest("none"))
    }

    @Test
    fun `clear empties the log`() {
        val log = ProbeLog()
        log.add(1L, "activity", 10, true)
        log.clear()
        assertEquals("probe: a cleared log must have no lines", 0, log.lines().size)
        assertNull("probe: a cleared log must have no latest line", log.latest("activity"))
    }
}
