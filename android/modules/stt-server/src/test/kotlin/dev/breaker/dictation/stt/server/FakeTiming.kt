package dev.breaker.dictation.stt.server

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Fake time sources for the engine tests. Public members:
 * - [FakeClock]: a [MonotonicClock]. [FakeClock.nanos] is settable; [FakeClock.advanceMs] moves it on.
 * - [FakeSleeper]: a [PollSleeper] that records each pause in [FakeSleeper.askedMs], advances the
 *   clock by that pause and returns at once. It never waits.
 * - [GateDeadlineTimer]: a [DeadlineTimer] that returns only when the test completes [GateDeadlineTimer.gate].
 *   It never completes by itself and it honours cancellation.
 * - [FakeTiming]: one clock, one sleeper and one deadline timer, bundled; [FakeTiming.timing] is the
 *   [Timing] to pass to the engine.
 */
internal class FakeClock(startNanos: Long = 0L) : MonotonicClock {
    private val now = AtomicLong(startNanos)

    /** The current time in nanoseconds. The test may set it. */
    var nanos: Long
        get() = now.get()
        set(value) {
            now.set(value)
        }

    override fun nowNanos(): Long = now.get()

    /** Moves the clock forward by [ms] milliseconds. */
    fun advanceMs(ms: Long) {
        now.addAndGet(ms * NANOS_PER_MS)
    }

    private companion object {
        const val NANOS_PER_MS: Long = 1_000_000L
    }
}

/** Records each requested pause in [askedMs] and advances [clock] by it. */
internal class FakeSleeper(private val clock: FakeClock) : PollSleeper {
    private val asked = AtomicReference<List<Long>>(emptyList())

    /** Every pause requested so far, in milliseconds, in the order they were asked for. */
    val askedMs: List<Long>
        get() = asked.get()

    override suspend fun sleep(ms: Long) {
        asked.updateAndGet { it + ms }
        clock.advanceMs(ms)
    }
}

/** A deadline that the test ends by completing [gate]. Until then the watchdog waits. */
internal class GateDeadlineTimer : DeadlineTimer {
    /** Completing this lets [await] return. Nothing completes it by default. */
    val gate: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun await(ms: Long) {
        gate.await()
    }
}

/** The clock, sleeper and deadline timer for one test, bundled into [timing]. */
internal class FakeTiming {
    val clock = FakeClock()
    val sleeper = FakeSleeper(clock)
    val deadline = GateDeadlineTimer()
    val timing: Timing = Timing(clock, sleeper, deadline)
}
