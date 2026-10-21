package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-to-one port over the tile's shared MIC_BUSY flag: every call forwards to the take-yield
 * controller, the busy read is the controller's own flag, the one listener is registered and cleared,
 * and the three begin answers go to the right controller entry — refused-as-taken keeps the flag set,
 * accepted clears it and notices the null line, and failed clears it while noticing NOTHING (a FAILED
 * face is not a busy one). The controller is built exactly as [TakeYieldHookTest] builds it, but the
 * taken-notice sink is captured here (that test discards it) so the accepted/failed split can be seen.
 */
internal class MicBusyPortForwarderTest {

    /** A real controller over the shared fakes, recording every takenNotice value into [taken]. */
    private fun controller(taken: MutableList<String?>): TakeYieldController {
        val log = ArrayList<String>()
        val take = FakeHomeTake(committer = CountingCommitter(), log = log)
        val settle = TakeSettle(InMemoryHistoryStore(log), FakeTakenClipboard())
        return TakeYieldController(
            background = FakeBackground(),
            main = FakeMainPost(),
            take = take,
            settle = settle,
            takenNotice = { line -> taken.add(line) },
            pushState = { },
            onSettled = { },
            generation = { 0 },
        )
    }

    /** B11: a begin refused as taken forwards to the controller's refused-open, which keeps the flag set. */
    @Test
    fun `a refused as taken begin keeps the busy flag set`() {
        val taken = ArrayList<String?>()
        val ctrl = controller(taken)
        val port = MicBusyPortForwarder(ctrl)
        port.beginRefusedAsTaken()
        assertEquals("app: forwarder: refused as taken reads busy true", true, port.micBusy())
        assertEquals("app: forwarder: refused as taken set the controller flag", true, ctrl.micBusy.get())
    }

    /**
     * B12: failed and accepted both clear the flag, so the flag alone cannot tell them apart — only the
     * taken-notice can. Failed fires NO takenNotice; accepted fires takenNotice(null). A forwarder that
     * routed failed to accepted would fire takenNotice(null) on a failed begin, which the first half kills.
     */
    @Test
    fun `a failed begin notices nothing but an accepted begin notices the null line`() {
        val failed = ArrayList<String?>()
        val failedCtrl = controller(failed)
        val failedPort = MicBusyPortForwarder(failedCtrl)
        failedPort.beginRefusedAsTaken()
        failedPort.beginFailed()
        assertTrue("app: forwarder: a failed begin never notices a null line", failed.none { it == null })

        val accepted = ArrayList<String?>()
        val acceptedCtrl = controller(accepted)
        val acceptedPort = MicBusyPortForwarder(acceptedCtrl)
        acceptedPort.beginRefusedAsTaken()
        acceptedPort.beginAccepted()
        assertTrue("app: forwarder: an accepted begin notices the null line", accepted.any { it == null })
    }

    /** B13: the busy read is the controller's own flag, read live — set it through the controller and compare. */
    @Test
    fun `the busy read mirrors the controller flag on both faces`() {
        val taken = ArrayList<String?>()
        val ctrl = controller(taken)
        val port = MicBusyPortForwarder(ctrl)
        ctrl.beginRefusedAsTaken()
        assertEquals("app: forwarder: busy read equals the flag after a refused as taken begin", ctrl.micBusy.get(), port.micBusy())
        ctrl.onBeginAccepted()
        assertEquals("app: forwarder: busy read equals the flag after an accepted begin", ctrl.micBusy.get(), port.micBusy())
    }

    /** B14: the one listener is registered while set and hears every flip, and cleared so a later flip is silent. */
    @Test
    fun `the busy listener hears each flip while set and nothing once cleared`() {
        val taken = ArrayList<String?>()
        val ctrl = controller(taken)
        val port = MicBusyPortForwarder(ctrl)
        val flips = ArrayList<Boolean>()
        port.setBusyListener { flips.add(it) }
        ctrl.beginRefusedAsTaken()   // false -> true
        ctrl.onBeginAccepted()       // true -> false
        assertEquals("app: forwarder: the set listener hears both flips", listOf(true, false), flips)
        port.setBusyListener(null)   // the one listener is cleared
        ctrl.beginRefusedAsTaken()   // a real flip, but no listener is set now
        ctrl.onBeginAccepted()       // another real flip, still silent
        assertEquals("app: forwarder: the cleared listener hears nothing more", listOf(true, false), flips)
    }
}
