package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a `connect` body that THROWS does to the probe's caller and to the pool.
 *
 * **Why this is a hole of its own.** [TcpConnector] documents that an
 * implementation must not let an exception escape, and the production
 * [SocketTcpConnector] honours that by folding every `IOException` into a
 * [ProbeOutcome]. So the throw case has no production author: the one code path
 * that could produce it is a seam implementation, which is exactly where a test
 * is the only thing that will ever exercise it. If that path is broken - a
 * connector that throws reaching the CALLER instead of being caught inside the
 * task - the failure lands on the dictation path, whose caller reads one boolean
 * and has no handler for anything else. So the promise "a throwing body costs
 * the caller nothing" has to be held here, by the test, or it is not held.
 *
 * **Why the throw cannot be caught by accident.** The throw is raised by the
 * CONNECTOR, not by the resolver: `Target.addresses` already wraps the
 * resolver in its own catch (see the source), so a throwing resolver would be
 * swallowed there and never reach the code this test is about. A connector that
 * throws is the one statement inside the task body that has no catch of its
 * own, which makes it the only place the FutureTask's capture can be observed
 * at all - remove that capture, or run the body on the caller's thread, and the
 * exception comes out of [TcpConnectivityProbe.isServerReachable] instead of
 * out of `task.get` as an `ExecutionException`, and this test fails with the
 * exception rather than with the boolean.
 *
 * **What it deliberately does NOT claim.** Nothing here says a throwing body is
 * cached, is the same answer as a refusal, or should be swallowed silently -
 * only that it is answered and that it does not escape. It installs no
 * `UncaughtExceptionHandler` (that would replace the behaviour rather than
 * observe it) and it builds no copy of `connect`: the body that throws runs
 * inside the production `FutureTask` at [TcpConnectivityProbe.runProbe], driven
 * by the production [ProbeExecutor].
 *
 * **The second half is the pool, not the boolean.** Both counters in
 * [ProbeExecutor] are released from `finally` blocks on the throw path, and if
 * either were not, one throw would leave the process-wide pool permanently
 * smaller than its promise and every later probe in this JVM would be refused
 * for capacity nobody was using. So the test finishes by requiring a healthy
 * address to still dial. The wait is bounded and retried rather than taken once:
 * `FutureTask.get` returns as soon as the outcome is set, which happens BEFORE
 * the submitted wrapper's `finally` hands the counters back, so a single
 * immediate attempt could lose that race on a loaded machine and report a leak
 * that is not there.
 */
class TcpConnectivityProbeThrowingConnectTest {

    @Test
    fun `a connect that throws answers not reachable and leaves the pool serving`() {
        val throwing = ThrowingConnector()
        val probe = TcpConnectivityProbe(
            { THROWING_HOST_URL },
            FakeClock(1_000L),
            FakeHostResolver(),
            throwing,
        )

        // The answer, not an exception: a throw out of this call would be a
        // caller-visible crash on the dictation path.
        assertFalse(
            cardFailure(
                "a connect that threw learned nothing about the server, so the only answer the caller can be given is not reachable; " +
                    "an exception here instead means the task's throw escaped the worker's capture and reached the caller, which is a " +
                    "caller-visible crash on a path that reads one boolean",
            ),
            probe.isServerReachable(),
        )

        // The production body really ran. Without this the assertion above would
        // also be satisfied by a probe that never dialled at all - refused,
        // unanswered, or answered from cache - and would pass for the wrong
        // reason.
        assertEquals(
            cardFailure(
                "the connector must have been asked to connect, or 'not reachable' below proves nothing about a throwing connect - it is " +
                    "the answer every refusal, every expired budget and every cached false also gives; ${throwing.callCount.get()} connects were made",
            ),
            1,
            throwing.callCount.get(),
        )
        assertEquals(
            cardFailure("the throw has to come off the production path, so the host dialled is the configured one"),
            listOf(THROWING_HOST),
            throwing.hosts.toList(),
        )

        // The pool survives the throw, and a fresh probe is what shows it: the
        // counters are process-wide, so a throw that failed to give them back
        // would refuse every later probe in this JVM.
        val drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        var answered: Boolean? = null
        while (System.nanoTime() < drainDeadline) {
            attempts++
            answered = TcpConnectivityProbe(
                { "https://throwing-drain-$attempts.invalid" },
                FakeClock(1_000L),
                FakeHostResolver(),
                RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
            ).isServerReachable()
            if (answered == true) break
        }
        if (answered != true) {
            throw AssertionError(
                cardFailure(
                    "after a connect body threw, a healthy address must still be admitted and dialled: the shared probe pool serves every " +
                        "probe in this JVM, and a throw that failed to return its counters would refuse it for capacity nobody was using. " +
                        "$attempts attempts inside ${DRAIN_BOUND_MS} ms all answered $answered",
                ),
            )
        }
    }

    /**
     * A connector that throws out of [connect], as a seam implementation that
     * breaks [TcpConnector]'s contract would.
     *
     * Records the dial before throwing, so "the production body really ran" is
     * a fact about this double and not an inference from the answer.
     */
    private class ThrowingConnector : TcpConnector {

        val callCount = AtomicInteger(0)
        val hosts = CopyOnWriteArrayList<String>()

        override fun connect(address: InetAddress, port: Int, timeoutMillis: Int): Boolean {
            callCount.incrementAndGet()
            hosts.add(address.hostName)
            throw IllegalStateException("connector contract broken on purpose")
        }
    }

    private companion object {
        const val THROWING_HOST = "throwing-connect.local"
        const val THROWING_HOST_URL = "https://$THROWING_HOST:8443"

        /** Ceiling on the drain. Each attempt can cost one [TcpConnectivityProbe.CONNECT_TIMEOUT_MS]. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
