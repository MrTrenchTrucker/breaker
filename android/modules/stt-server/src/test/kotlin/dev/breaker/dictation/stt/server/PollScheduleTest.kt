package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PollScheduleTest {

    private fun firstDelays(limits: ServerLimits, count: Int): List<Long> {
        val schedule = PollSchedule(limits)
        return (0 until count).map { schedule.delayMs(it) }
    }

    @Test
    fun `default sequence is 500 750 1125 1687 2000 and then stays at 2000`() {
        assertEquals(listOf(500L, 750L, 1125L, 1687L, 2000L, 2000L), firstDelays(ServerLimits(), 6))
        assertEquals(2000L, PollSchedule(ServerLimits()).delayMs(40))
    }

    @Test
    fun `the schedule never exceeds the delay cap`() {
        assertEquals(listOf(500L, 750L, 1000L, 1000L), firstDelays(ServerLimits(pollDelayCapMs = 1_000L), 4))
    }

    @Test
    fun `a non-default first delay below the cap grows from that delay`() {
        assertEquals(
            listOf(1_000L, 1_500L, 2_000L, 2_000L),
            firstDelays(ServerLimits(firstPollDelayMs = 1_000L, pollDelayCapMs = 2_000L), 4),
        )
    }

    @Test
    fun `a first delay equal to the cap stays at the cap`() {
        assertEquals(listOf(2_000L, 2_000L, 2_000L), firstDelays(ServerLimits(firstPollDelayMs = 2_000L), 3))
    }

    @Test
    fun `a small first delay still grows by at least one each step`() {
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 5L),
            firstDelays(ServerLimits(firstPollDelayMs = 1L, pollDelayCapMs = 5L), 6),
        )
    }

    @Test
    fun `limits refuse a first delay above the cap so the schedule never starts above it`() {
        try {
            ServerLimits(firstPollDelayMs = 2_001L, pollDelayCapMs = 2_000L)
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("a first delay above the cap was accepted")
    }

    @Test
    fun `the delay is the same every time it is asked for the same index`() {
        val schedule = PollSchedule(ServerLimits())
        assertTrue(schedule.delayMs(3) == schedule.delayMs(3))
        assertEquals(1687L, schedule.delayMs(3))
    }
}
