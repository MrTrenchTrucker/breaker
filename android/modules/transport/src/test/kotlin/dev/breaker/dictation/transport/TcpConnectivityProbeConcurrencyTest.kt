package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One probe, asked from many threads at once.
 *
 * What is worth holding on to here is that the storm costs ONE dial, and that
 * every caller gets an answer rather than a casualty. The cache is the shared
 * mutable state: several threads reading and writing it at the same instant is
 * where a torn or half-published entry would show up, and a probe that threw
 * instead of answering would surface as a dictation that failed for no reason
 * the user could act on.
 *
 * Deliberately NOT asserted: that exactly one dial happens for N concurrent
 * callers. Single-flight is not a promise this module makes - it is an
 * optimisation nobody asked for - so a test pinning it would fail the moment
 * someone legitimately made the dials concurrent, and would be describing a
 * choice rather than a contract. What IS asserted is the weaker, promised
 * thing: the dial count is unchanged by the storm, and the answer survives it.
 */
class TcpConnectivityProbeConcurrencyTest : ProbePoolIsolation() {

    @Test
    fun `a storm of concurrent questions costs one dial and loses no caller`() {
        val threads = 8
        val callsPerThread = 50
        val expectedAnswers = threads * callsPerThread

        val clock = FakeClock(1_000L)
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe({ "https://box.local" }, clock, resolver, connector)

        val startTogether = CountDownLatch(1)
        val finished = CountDownLatch(threads)
        val answers = Collections.synchronizedList(mutableListOf<Boolean>())
        val casualties = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (0 until threads).map { index ->
            Thread {
                try {
                    startTogether.await()
                    repeat(callsPerThread) { answers.add(probe.isServerReachable()) }
                } catch (t: Throwable) {
                    // Carried into the assertion below rather than thrown on the
                    // thread: an exception nobody collects leaves a dead worker
                    // that reads as a pass.
                    casualties.add(t)
                } finally {
                    finished.countDown()
                }
            }.apply {
                name = "breaker-transport-probe-worker-$index"
                isDaemon = true
            }
        }

        workers.forEach { it.start() }
        startTogether.countDown()
        workers.forEach { worker ->
            worker.join(JOIN_TIMEOUT_MS)
            assertFalse(
                cardFailure("a caller was still waiting on the probe ${JOIN_TIMEOUT_MS} ms after the storm was released - a probe that can block a thread indefinitely is a dictation that never comes back"),
                worker.isAlive,
            )
        }

        assertTrue(
            cardFailure("every worker must have finished before the answer count is read, or the count describes a storm still running"),
            finished.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertEquals(
            cardFailure("a caller must never lose its question to another caller's - one of $threads threads died and took $expectedAnswers possible answers with it"),
            emptyList<Throwable>(),
            casualties.toList(),
        )
        assertEquals(
            cardFailure("all $expectedAnswers questions must have been answered, none lost and none answered twice"),
            expectedAnswers,
            answers.size,
        )
        assertFalse(
            cardFailure("a caller that was answered 'not reachable' was given an answer the server's own accepted dial contradicts"),
            answers.any { !it },
        )

        // Asserted on the count rather than on the answer, because a dial count
        // says what happened and a boolean only says what came back.
        assertEquals(
            cardFailure("a cached answer costs one dial however many callers ask for it - $expectedAnswers questions must not become $expectedAnswers dials"),
            1,
            connector.callCount,
        )

        clock.instant += 30_000L
        assertTrue(
            cardFailure("after the cache window closes the next question must find the server again"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("the storm must leave a usable cache behind: once the 30 s window closes the next question must dial again, exactly once"),
            2,
            connector.callCount,
        )
        assertEquals(
            cardFailure("every dial under contention must still have gone to the configured host and no other"),
            listOf("box.local", "box.local"),
            connector.hosts,
        )
    }

    private companion object {
        /**
         * How long a worker is given to finish before it is called hung.
         *
         * Generous on purpose: this fails a thread that cannot be answered at
         * all, and should not fail one that is merely slow on a loaded machine.
         */
        const val JOIN_TIMEOUT_MS = 30_000L
    }
}
