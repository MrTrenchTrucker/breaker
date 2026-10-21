package dev.breaker.dictation.stt.server

/**
 * Pause before each poll of a job.
 *
 * Poll 0 waits the first delay. Each later poll waits three halves of the one before, or at
 * least one millisecond more when that is larger, and no poll waits longer than the delay cap.
 * Integer arithmetic only, so the same limits always give
 * the same sequence. The schedule is pure and keeps no state between calls.
 */
internal class PollSchedule(private val limits: ServerLimits) {

    /** Delay in milliseconds before the poll at [index], where index 0 is the first poll. */
    fun delayMs(index: Int): Long {
        val cap = limits.pollDelayCapMs
        var current = minOf(limits.firstPollDelayMs, cap)
        var step = 0
        while (step < index && current < cap) {
            current = minOf(maxOf(current * 3L / 2L, current + 1L), cap)
            step++
        }
        return current
    }
}
