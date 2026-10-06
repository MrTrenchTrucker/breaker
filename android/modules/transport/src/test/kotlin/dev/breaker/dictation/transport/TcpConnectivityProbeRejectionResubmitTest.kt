package dev.breaker.dictation.transport

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the pool's OWN rejection handler fires: the dispatcher
 * cancels the launched job and resubmits the launch block onto Dispatchers.IO,
 * and the block's `finally` is the only place the counters come back.
 *
 * **The mechanism.** [ProbeExecutor.executeReporting] claims both counters -
 * `occupied` and `bodies` - and returns them ONLY in the launch block's
 * `finally`, the whole containment the pool's rejection-handler comment relies
 * on. On the rejection path the dispatcher's catch on
 * [java.util.concurrent.RejectedExecutionException] cancels the launched job
 * first, then resubmits the SAME block onto Dispatchers.IO. The resubmitted
 * block is a dispatched task whose `run` sees the cancelled job and completes
 * the coroutine as cancelled without invoking the block -
 * [kotlinx.coroutines.CoroutineStart.DEFAULT] documents that a job cancelled
 * before it started does not start its execution at all. So the `finally`
 * would not run and `occupied` and `bodies` would each stay at 1, for the life of the
 * process. The keyed path routes through [ProbeSingleFlight.submit], which
 * installs a per-host mark in `inFlight` and removes it in that same `finally`,
 * so the rejection leaks the mark too.
 *
 * **Reaching the pool.** `claim` is the only refuser in the normal path - it
 * refuses at `bodies >= POOL_MAX_THREADS`, the pool's own `maximumPoolSize` -
 * so an admitted body always gets a worker and the pool never rejects. To make
 * the pool reject, this test reaches the hand-built
 * [java.util.concurrent.ThreadPoolExecutor] behind [ProbeExecutor]'s
 * `asCoroutineDispatcher()` wrapper and saturates its threads DIRECTLY with
 * three raw [Runnable]s that park on latches this test owns, bypassing
 * `claim`: the pool is at `maximumPoolSize` while `occupied` and `bodies` are
 * still 0, so the next `executeReporting` admits (claim sees 0/0), launches,
 * and the pool rejects - the case under test.
 *
 * **Severity.** The rejection path is a BACKSTOP, reachable only when the pool
 * is saturated by a route that does not go through `claim` (a version of
 * `claim` that drops the `bodies` check, a shared pool, or a config that
 * lowers `maximumPoolSize`).
 *
 * **Cleanup.** The `finally` below releases the parked latches and returns the
 * exact counters the leak left (guarded to `== 1`), so the shared pool's
 * `@After` idle check in [ProbePoolIsolation] passes and the next test starts
 * clean - the leak stays observable on this test's own assertions, not as a
 * spurious `@After` failure.
 */
class TcpConnectivityProbeRejectionResubmitTest : ProbePoolIsolation() {

    // --- the unkeyed case: both counters leak -------------------------------

    @Test
    fun `after the pool rejects a submitted body both counters come back`() {
        val poolExecutor = probePoolExecutor()
        val parked = List(POOL_MAX_THREADS) { index -> ParkedWorker("rejected-parked-$index") }
        val bodyRan = CountDownLatch(1)
        val escaped = CopyOnWriteArrayList<Throwable>()

        var admitted: ProbeExecutor.Refusal? = null
        var countersCameBack = false
        var bodyRanState = false
        var finalReading = ProbePoolReading.read()
        try {
            // Saturate the pool's OWN threads, bypassing claim, so occupied
            // and bodies stay 0 while the pool is at maximumPoolSize.
            for (worker in parked) {
                poolExecutor.execute(Runnable { worker.hold.await(PARK_AWAIT_MS, TimeUnit.MILLISECONDS) })
            }
            awaitPoolActive(poolExecutor, POOL_MAX_THREADS)

            val preCallReading = ProbePoolReading.read()
            assertTrue(
                cardFailure(
                    "the pool was saturated by ${parked.size} parked raw workers that bypass claim, so occupied and bodies must be 0 before the call; the reading shows $preCallReading",
                ),
                preCallReading.occupied == 0 && preCallReading.bodies == 0,
            )

            // The admission: claim sees 0/0 and admits, so executeReporting
            // returns null and launches. The pool rejects, the dispatcher
            // cancels the job and resubmits the block onto IO, and the block
            // would not run - so bodyRan would never be counted down and the counters
            // are never released.
            admitted = runCatching {
                ProbeExecutor.executeReporting(Runnable { bodyRan.countDown() })
            }.onFailure { escaped.add(it) }.getOrNull()

            // Bounded wait for the counters to come back - the promise. If
            // the launch block's finally ran (on the pool or on IO), occupied
            // and bodies are back to 0. If it never ran, they stay at 1.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COUNTER_BOUND_MS)
            while (System.nanoTime() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
                finalReading = ProbePoolReading.read()
                if (finalReading.occupied == 0 && finalReading.bodies == 0) {
                    countersCameBack = true
                    break
                }
            }
            bodyRanState = bodyRan.await(0, TimeUnit.MILLISECONDS)
        } finally {
            parked.forEach { it.hold.countDown() }
            returnLeakedCounters()
            drainProbeExecutor()
        }

        // --- the counters came back ------------------------------------------
        assertTrue(
            cardFailure(
                "the launch block's finally in executeReporting is where occupied and bodies are released, and it is the containment the pool's rejection-handler comment relies on. After ${COUNTER_BOUND_MS} ms the pool still holds $finalReading - a silent, permanent over-count that lifts both caps. The finally must run, on whichever thread the block ends up on, and it does not. The body ran: $bodyRanState, the admission was: $admitted",
            ),
            countersCameBack,
        )
        assertTrue(
            cardFailure(
                "no exception may reach the caller from the rejection path: the dispatcher catches RejectedExecutionException and resubmits, so the caller of executeReporting must see a clean admission, not a throw. Thrown: $escaped",
            ),
            escaped.isEmpty(),
        )
    }

    // --- the keyed case: the per-host mark leaks too ------------------------

    @Test
    fun `after the pool rejects a keyed submitted body the in-flight mark comes back`() {
        val poolExecutor = probePoolExecutor()
        val parked = List(POOL_MAX_THREADS) { index -> ParkedWorker("rejected-keyed-parked-$index") }
        // A fresh host, nothing else probes it, so the cleanup's removal of
        // this host's key cannot evict any other probe's mark.
        val host = "rejected-keyed-${System.nanoTime()}.invalid"
        val bodyRan = CountDownLatch(1)

        var admitted: ProbeExecutor.Refusal? = null
        var markCameBack = false
        var bodyRanState = false
        var finalReading = ProbePoolReading.read()
        try {
            for (worker in parked) {
                poolExecutor.execute(Runnable { worker.hold.await(PARK_AWAIT_MS, TimeUnit.MILLISECONDS) })
            }
            awaitPoolActive(poolExecutor, POOL_MAX_THREADS)

            val preCallReading = ProbePoolReading.read()
            assertTrue(
                cardFailure(
                    "the pool was saturated by ${parked.size} parked raw workers that bypass claim, so occupied and bodies must be 0 before the call; the reading shows $preCallReading",
                ),
                preCallReading.occupied == 0 && preCallReading.bodies == 0,
            )

            // The keyed admission, driven through the REAL production path:
            // executeReporting(host, task) routes through
            // ProbeSingleFlight.submit, which installs a per-host mark in
            // inFlight before the submit and removes it only (a) in the
            // launch block's finally, or (b) in submit's backstop
            // `if (refusal != null) inFlight.remove(key, mark)`.
            // submit then calls the unkeyed executeReporting(single), which
            // admits (claim sees 0/0), launches, and the pool rejects. The
            // dispatcher cancels the job and resubmits the block onto IO, and
            // the block - which holds the mark's finally - would not run.
            // executeReporting(single) returns null (admitted), so submit's
            // backstop does not fire. The mark stays.
            admitted = runCatching {
                ProbeExecutor.executeReporting(host, Runnable { bodyRan.countDown() })
            }.getOrNull()

            // Bounded wait for the mark to come back - the promise.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COUNTER_BOUND_MS)
            while (System.nanoTime() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
                finalReading = ProbePoolReading.read()
                if (finalReading.marks == 0) {
                    markCameBack = true
                    break
                }
            }
            bodyRanState = bodyRan.await(0, TimeUnit.MILLISECONDS)
        } finally {
            parked.forEach { it.hold.countDown() }
            returnLeakedCounters()
            returnLeakedMark(host)
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure(
                "the per-host mark for $host must come back: submit installs it before the submit and removes it in the launch block's finally. After ${COUNTER_BOUND_MS} ms the pool still holds $finalReading - the mark stays in inFlight, and every later probe of $host is refused with HOST_IN_FLIGHT - answered not reachable with no dial - for the life of the process. The body ran: $bodyRanState, the admission was: $admitted",
            ),
            markCameBack,
        )
    }

    // --- shared plumbing -----------------------------------------------------

    /**
     * Reaches the pool's own [ThreadPoolExecutor] through the dispatcher
     * wrapper, so this test can saturate the real pool's threads directly,
     * bypassing [ProbeExecutor.claim].
     *
     * [ProbeExecutor.pool] is the `asCoroutineDispatcher()` wrapper over the
     * hand-built pool. `asExecutor()` hands back the original executor for an
     * `ExecutorCoroutineDispatcher`, which is the [ThreadPoolExecutor] with
     * the `SynchronousQueue` and the rejection handler that throws
     * [java.util.concurrent.RejectedExecutionException].
     */
    private fun probePoolExecutor(): ThreadPoolExecutor {
        val poolField: Field = ProbeExecutor::class.java.getDeclaredField("pool").apply { isAccessible = true }
        // Field.get is Any!; asExecutor() is an extension on CoroutineDispatcher.
        val pool = poolField.get(ProbeExecutor) as CoroutineDispatcher
        val executor = (pool as? kotlinx.coroutines.ExecutorCoroutineDispatcher)?.executor
            ?: pool.asExecutor()
        return executor as? ThreadPoolExecutor
            ?: throw AssertionError(
                cardFailure(
                    "the pool's substrate must be the hand-built ThreadPoolExecutor; asExecutor() handed back $executor, which is not a ThreadPoolExecutor. The test cannot saturate a pool it cannot see",
                ),
            )
    }

    /** One raw worker that parks on a latch this test owns, holding a pool thread. */
    private class ParkedWorker(private val name: String) {
        val hold = CountDownLatch(1)
    }

    /**
     * Waits, bounded, until the pool's own thread count reaches [expected],
     * which is the observable event that the pool is at maximumPoolSize and
     * the next execute will be rejected.
     */
    private fun awaitPoolActive(pool: ThreadPoolExecutor, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COUNTER_BOUND_MS)
        var attempts = 0
        while (pool.activeCount < expected && System.nanoTime() < deadline) {
            attempts++
            Thread.sleep(POLL_INTERVAL_MS)
        }
        if (pool.activeCount < expected) {
            throw AssertionError(
                cardFailure(
                    "the pool's own thread count reached ${pool.activeCount} of the $expected this test parked, over $attempts polls, so the pool was not actually saturated and the next execute would not be rejected - the test would then be measuring the normal admitted path, not the rejection path",
                ),
            )
        }
    }

    /**
     * Returns the two counters a leaked admission is holding, so a test that
     * leaves the pool saturated does not hand a busy pool to every later test
     * in this JVM.
     *
     * Reads `occupied` and `bodies` reflectively and decrements each by 1 if
     * it is still at 1 - the exact release the launch block's finally would
     * have done. The `== 1` guard is load-bearing: it returns only the ONE
     * slot/one thread this test's admission leaked. If the finally DID run,
     * both are already 0 and this is a no-op, so it cannot drive a counter
     * negative; if a LATER admission had raised a counter above 1, this test
     * would not touch it - it returns only its own leak, never someone
     * else's count.
     */
    private fun returnLeakedCounters() {
        for (name in listOf("occupied", "bodies")) {
            try {
                val field: Field = ProbeExecutor::class.java.getDeclaredField(name).apply { isAccessible = true }
                val counter = field.get(ProbeExecutor) as? java.util.concurrent.atomic.AtomicInteger
                if (counter != null && counter.get() == 1) {
                    counter.decrementAndGet()
                }
            } catch (_: NoSuchFieldException) {
                // The counter is private; if it moved, the reading below names it.
            }
        }
    }

    /**
     * Removes the per-host mark a leaked keyed admission is holding, so a test
     * that leaves a mark does not make every later probe of that host answer
     * not reachable with no dial for the life of the process.
     *
     * The mark is the one [ProbeSingleFlight.submit] installed for [host] and
     * never removed, because the launch block's `finally` would not run and the
     * submit's backstop did not fire. This test's host is fresh - nothing else
     * probes it in this JVM - so an unconditional key removal is correct here
     * and cannot evict a later probe's mark. The key is the host lower-cased
     * with the ROOT locale, matching [ProbeSingleFlight]'s key folding.
     */
    private fun returnLeakedMark(host: String) {
        try {
            val field: Field = ProbeSingleFlight::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val inFlight = field.get(ProbeSingleFlight) as ConcurrentHashMap<String, Any>
            inFlight.remove(host.lowercase(java.util.Locale.ROOT))
        } catch (_: NoSuchFieldException) {
            // The map is private; if it moved, the reading below names it.
        }
    }

    /**
     * Waits, bounded, for the shared probe pool to come back.
     *
     * [ProbeExecutor] is a process-wide singleton, so a worker left parked or a
     * counter left leaked turns every LATER test in this JVM into a refused
     * task and a false "not reachable". The drain runs a FRESH probe with a
     * FRESH clock against a host nothing has cached, so a `true` from it can
     * only have come from a dial that really ran on a shared worker.
     */
    private fun drainProbeExecutor() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        while (System.nanoTime() < deadline) {
            attempts++
            val drain = TcpConnectivityProbe(
                { "https://rejection-resubmit-drain-$attempts.invalid" },
                FakeClock(1_000L),
                FakeHostResolver(),
                RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
            )
            if (drain.isServerReachable()) return
        }
        throw AssertionError(
            cardFailure(
                "the shared probe worker was still busy ${DRAIN_BOUND_MS} ms after this test released its latches, over $attempts attempts - the one pool in [ProbeExecutor] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it",
            ),
        )
    }

    private companion object {
        /**
         * The thread ceiling these tests fill: [ProbeExecutor.MAX_WEDGED_PROBES]
         * plus the one headroom thread, which is the pool's own
         * `maximumPoolSize` and the number a saturated pool holds.
         */
        const val POOL_MAX_THREADS = ProbeExecutor.MAX_WEDGED_PROBES + 1
        /** Ceiling on the bounded waits: generous for a slow CI box, short enough that a leak fails instead of hanging. */
        const val COUNTER_BOUND_MS = 5_000L

        /** Ceiling on a parked worker's own wait, in case a latch is never opened. */
        const val PARK_AWAIT_MS = 10_000L

        /** Pause between counter polls, so the act of asking does not keep the pool busy. */
        const val POLL_INTERVAL_MS = 20L
        /** Ceiling on the drain, generous because each attempt can cost a budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
