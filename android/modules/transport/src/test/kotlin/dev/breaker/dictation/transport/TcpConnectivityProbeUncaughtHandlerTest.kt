package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a connector that violates its contract puts its exception.
 *
 * [TcpConnector] documents that an implementation must not let an exception
 * escape — "did not connect" is the answer for every failure — and the
 * production [SocketTcpConnector] honours that by folding every I/O failure
 * into a [ProbeOutcome]. A seam implementation that throws anyway is a broken
 * contract, and this test pins what the module owes a caller when it meets one
 * at the seam: the caller still reads its one boolean, and the exception does
 * not surface on any thread's uncaught-exception handler. That is the promise
 * [TcpConnectivityProbe.runProbe] makes by publishing a connector failure to
 * the caller through its deferred and ending the body normally instead of
 * re-throwing it out of the body; without that, a throw out of a body runs on
 * a worker and, having nowhere else to go, lands on the uncaught-exception
 * handler of whatever thread the machinery last resorted to.
 *
 * **How it is observed.** The test installs a recording default
 * uncaught-exception handler for the duration of the probe, drives one real
 * probe of a host whose connector throws, and then requires that the recorder
 * is empty. It restores the previous handler in a `finally` so a failure here
 * cannot leave a recording handler in place for the rest of the suite. The
 * wait for the pool to come back idle runs before the handler is read, and a
 * short bounded settle follows it: the handler, where it is reached at all, is
 * invoked by the machinery after the body has ended, so reading it the instant
 * the pool is idle could miss a dispatch that is still on its way.
 *
 * **What it does not duplicate.** [TcpConnectivityProbeThrowingConnectTest]
 * pins that a throwing connector is ANSWERED (not reachable) and that the pool
 * still serves a later probe; this class pins the one fact that one does not:
 * that the throw leaves no trace on any thread's uncaught-exception handler.
 * The two together are the whole contract for a broken connector at the seam.
 */
class TcpConnectivityProbeUncaughtHandlerTest : ProbePoolIsolation() {

    @Test
    fun `a connector that throws leaves no uncaught exception on any thread`() {
        val seen = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> seen.add(error) }
        try {
            val calls = AtomicInteger(0)
            val connector = object : TcpConnector {
                override fun connect(address: InetAddress, port: Int, timeoutMillis: Int): Boolean {
                    calls.incrementAndGet()
                    throw IllegalStateException("connector contract broken on purpose")
                }
            }
            val probe = TcpConnectivityProbe(
                { "https://uncaught-handler.local:8443" },
                FakeClock(1_000L),
                FakeHostResolver(),
                connector,
            )
            assertFalse(
                cardFailure("a throwing connector must still answer not reachable to its caller: the failure is folded into the answer, not thrown at the caller"),
                probe.isServerReachable(),
            )
            assertEquals(
                cardFailure("the connector must have been dialled once: the answer above is only meaningful if the production body really reached the seam"),
                1,
                calls.get(),
            )
            // The body must have given its counters back before the handler is
            // read, or this class would hand a busy pool to the next class in
            // the JVM; see the class KDoc on [ProbePoolIsolation].
            awaitProbePoolIdle(context = "after a connector that threw")
            // A short bounded settle: the uncaught handler, where it is reached
            // at all, is invoked by the machinery after the body has ended, so
            // the read below must not race a dispatch that is still on its way.
            Thread.sleep(SETTLE_MS)
            assertTrue(
                cardFailure("a throwing connector reached the uncaught-exception handler - the module's promise is that a seam failure is answered, not surfaced on a thread's uncaught handler: $seen"),
                seen.isEmpty(),
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private companion object {
        /** Settling window after the pool is idle; see the method comment. */
        const val SETTLE_MS = 300L
    }
}
