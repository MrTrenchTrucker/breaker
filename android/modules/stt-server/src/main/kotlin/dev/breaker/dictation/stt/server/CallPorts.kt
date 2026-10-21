package dev.breaker.dictation.stt.server

import kotlinx.coroutines.delay

/** Source of a monotonic time in nanoseconds. The call budget is measured with it. */
internal fun interface MonotonicClock {
    fun nowNanos(): Long
}

/**
 * Pause between two polls of a job. Returns once [ms] milliseconds have passed.
 *
 * This is a plain interface rather than a fun interface because its member is suspending.
 */
internal interface PollSleeper {
    suspend fun sleep(ms: Long)
}

/**
 * Deadline for one call. Returns once [ms] milliseconds have passed; only the watchdog uses it.
 *
 * This is a plain interface rather than a fun interface because its member is suspending.
 */
internal interface DeadlineTimer {
    suspend fun await(ms: Long)
}

/** The three time sources one call needs, bundled so tests can replace all of them at once. */
internal class Timing(
    val clock: MonotonicClock,
    val sleeper: PollSleeper,
    val deadline: DeadlineTimer,
) {
    companion object {
        /** The real clock and real delays. */
        val SYSTEM: Timing = Timing(
            clock = object : MonotonicClock {
                override fun nowNanos(): Long = System.nanoTime()
            },
            sleeper = object : PollSleeper {
                override suspend fun sleep(ms: Long) = delay(ms)
            },
            deadline = object : DeadlineTimer {
                override suspend fun await(ms: Long) = delay(ms)
            },
        )
    }
}
