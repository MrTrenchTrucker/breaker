package dev.breaker.dictation.transport

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the CALLER is interrupted while a probe is in flight.
 *
 * The probe answers on the caller's thread and parks it, waiting for a body on
 * a worker to finish. The only other way out of that park, other than the body
 * finishing, is an interrupt of the caller, and this is the only test that
 * drives it. [TcpConnectivityProbe] bridges the blocked wait through a
 * `runBlocking`, and kotlinx-coroutines 1.11.0 turns an interrupted blocked
 * thread into a thrown `InterruptedException` (`jvm/src/Builders.kt:58` cancels
 * the job with an `InterruptedException`, `:68` re-throws it unwrapped). So the
 * caller must come back the way a refused or timed-out probe does: with
 * `false`, nothing learned, nothing cached, and with its interrupt flag re-set
 * for the code further up the stack that set it - never with an exception,
 * which is the thing a caller on the dictation path has no handler for.
 *
 * **Two facts, asserted separately.** Returning `false` and re-setting the
 * flag are independent: a build that swallows the interrupt could still answer
 * `false`, and one that answers `false` could still eat the flag. So the test
 * checks both, and it is built to fail loudly if either is wrong - in
 * particular a wrapped exception must not pass as a `false`. The re-arm is
 * checked with [Thread.isInterrupted], which reads the flag without clearing
 * it; the flag is then cleared in a `finally` on the probe thread so it cannot
 * leak into whatever reuses that thread.
 *
 * **The interrupt is aimed at the parked caller, not the worker.** The probe
 * runs on its own thread so the interrupt can be aimed at the thread that is
 * actually parked in the probe's wait. The worker is never interrupted: a name
 * lookup or a dial does not stop on one, and interrupting it would change the
 * fact under test.
 *
 * **The body ends on its own terms, and that end releases everything.** When
 * the caller is interrupted the bridge is cancelled, but the dispatched body is
 * not a structured child of that cancelled job - it is a `launch` on the pool.
 * It therefore keeps going on its worker and ends when this test releases it,
 * and it is that end, in the submitted wrapper's `finally`, that gives the slot
 * and the thread back and un-marks the host. The test holds the body in the
 * connector until after the caller has been interrupted, then releases it and
 * waits for the pool to come back idle - which asserts, with no sleep, that the
 * interrupt did not leak a slot, a thread or a single-flight mark.
 *
 * No real DNS and no real sockets: the resolver is the shared fake, the
 * connector is the shared [RecordingConnector], and every wait is a bounded
 * latch or a bounded poll over the shared pool.
 */
class TcpConnectivityProbeInterruptTest : ProbePoolIsolation() {

    @Test
    fun `an interrupted caller gets false with the interrupt flag re-set, and the pool comes back clean`() {
        val host = "interrupted-caller.invalid"
        // The body signals that it has entered the connector, then holds there
        // until this test releases it, so the body is still running when the
        // interrupt lands on the caller.
        val entered = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.CONNECTED),
            beforeAnswer = { _ ->
                entered.countDown()
                hold.await(ENTERED_BOUND_MS, TimeUnit.MILLISECONDS)
            },
        )
        val probe = TcpConnectivityProbe({ "https://$host" }, FakeClock(1_000L), FakeHostResolver(), connector)

        // The probe runs on its own thread so the interrupt can be aimed at the
        // thread that is actually parked in the probe's wait. Its result and
        // the re-arm are read back from that same thread.
        val answers = CopyOnWriteArrayList<Boolean>()
        val rearmSeen = CopyOnWriteArrayList<Boolean>()
        val escaped = CopyOnWriteArrayList<Throwable>()
        val probeThread = Thread {
            try {
                answers.add(probe.isServerReachable())
                // The arm re-arms this thread's flag; read it without clearing
                // it, then clear it here so it cannot leak past this test.
                rearmSeen.add(Thread.currentThread().isInterrupted())
            } catch (thrown: Throwable) {
                escaped.add(thrown)
            } finally {
                // Clear the flag on this thread whether or not the arm ran, so
                // a re-used probe thread never starts the next test interrupted.
                Thread.interrupted()
            }
        }
        probeThread.start()

        var released = false
        try {
            // The body has entered the connector and is parked, so the caller is
            // parked in its wait - the only state the interrupt can hit.
            assertTrue(
                cardFailure("the probe's worker must have entered the connector within $ENTERED_BOUND_MS ms before this test interrupts the caller: it never did, so there was no in-flight probe to interrupt and the assertions below are about nothing"),
                entered.await(ENTERED_BOUND_MS, TimeUnit.MILLISECONDS),
            )
            // Interrupt the caller - the thread the probe parked - while its
            // body is still running.
            probeThread.interrupt()
            // join(ms) returns Unit, so liveness is the assertion: a caller
            // still alive after the bound was not woken by the interrupt.
            probeThread.join(CALLER_BOUND_MS)
            assertTrue(
                cardFailure("the probe's caller thread did not return within $CALLER_BOUND_MS ms of being interrupted: an interrupted blocked caller must be woken and answered, not held. Escaped: $escaped"),
                !probeThread.isAlive,
            )
            // Release the held body so it ends, then wait for the pool to come
            // back - the interrupt must not have leaked a slot, thread or mark.
            released = true
            hold.countDown()
            awaitProbePoolIdle(context = "after interrupting the caller of an in-flight probe of $host")
        } finally {
            // Always open the hold so a failed wait cannot park a worker for the
            // next test, and always release the pool it may have left busy.
            if (!released) hold.countDown()
            awaitProbePoolIdle(context = "teardown after the interrupted-caller probe of $host")
        }

        // --- the answer, and the flag ------------------------------------------

        assertEquals(
            cardFailure("an interrupted caller must be ANSWERED with false - nothing was measured, the dial was not finished by this caller - and must not be $answers. Thrown: $escaped"),
            listOf(false),
            answers,
        )
        assertTrue(
            cardFailure("the caller must come back with its interrupt flag RE-SET, so the thread that set it still sees it: the flag read was $rearmSeen. A probe that answers false but eats the interrupt would leave a thread that asked to be stopped still parked with no signal - the re-arm is the caller's interrupt being handed back to whoever set it"),
            rearmSeen.size == 1 && rearmSeen[0],
        )
        assertTrue(
            cardFailure("an interrupted probe must ANSWER, not throw: the caller is the dictation path and has no handler for a failure it cannot see through a boolean, and a wrapped exception in particular must not pass as a false. Thrown: $escaped"),
            escaped.isEmpty(),
        )

        // --- the body's end released everything ---------------------------------

        assertEquals(
            cardFailure("the body was released to run to the end of its dial, so the connector must have recorded the one connection it was asked to make: it recorded ${connector.callCount}. If it is zero the body never reached the dial and the pool-idle wait above was about nothing"),
            1,
            connector.callCount,
        )
    }

    private companion object {
        /** How long the body may take to enter the connector before the test gives up. */
        const val ENTERED_BOUND_MS = 5_000L
        /** How long the interrupted caller may take to be woken and answered. */
        const val CALLER_BOUND_MS = 10_000L
    }
}
