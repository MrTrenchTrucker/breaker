package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The take-yield controller's busy-flag hook: the listener fires exactly when the flag flips, never on
 * an unchanged set, and each of the controller's six entries still sets or clears the flag as before.
 */
internal class TakeYieldHookTest {

    /** A controller over the shared fakes, with [fired] recording every value the listener is given. */
    private fun controller(fired: MutableList<Boolean>): TakeYieldController {
        val log = ArrayList<String>()
        val take = FakeHomeTake(committer = CountingCommitter(), log = log)
        val settle = TakeSettle(InMemoryHistoryStore(log), FakeTakenClipboard())
        return TakeYieldController(
            background = FakeBackground(),
            main = FakeMainPost(),
            take = take,
            settle = settle,
            takenNotice = { },
            pushState = { },
            onSettled = { },
            generation = { 0 },
        ).apply { onBusyChanged = { value -> fired.add(value) } }
    }

    /** A flip fires the listener once each way; an unchanged set fires nothing. */
    @Test
    fun `a flip fires the listener once and an unchanged set fires nothing`() {
        val fired = ArrayList<Boolean>()
        val ctrl = controller(fired)
        ctrl.beginRefusedAsTaken()
        assertEquals("app: hook: the flip to true fires once", listOf(true), fired)
        assertEquals("app: hook: the flag is set", true, ctrl.micBusy.get())
        ctrl.beginRefusedAsTaken()
        assertEquals("app: hook: an unchanged set fires nothing", listOf(true), fired)
        ctrl.onBeginAccepted()
        assertEquals("app: hook: the flip to false fires once", listOf(true, false), fired)
        assertEquals("app: hook: the flag is cleared", false, ctrl.micBusy.get())
        ctrl.onBeginAccepted()
        assertEquals("app: hook: an unchanged clear fires nothing", listOf(true, false), fired)
    }

    /** Each of the six entries sets the flag (taken, refused) or clears it (accepted, failed, take-ended, disarm). */
    @Test
    fun `each busy entry point sets or clears the flag`() {
        val fired = ArrayList<Boolean>()
        val ctrl = controller(fired)
        ctrl.onTakeMicTaken()
        assertEquals("app: hook: a taken cut sets the flag", true, ctrl.micBusy.get())
        ctrl.beginRefusedAsTaken()
        assertEquals("app: hook: a refused open keeps the flag set", true, ctrl.micBusy.get())
        ctrl.onBeginAccepted()
        assertEquals("app: hook: an accepted begin clears the flag", false, ctrl.micBusy.get())
        ctrl.onBeginFailed()
        assertEquals("app: hook: a failed begin keeps the flag cleared", false, ctrl.micBusy.get())
        ctrl.onTakeEndedCleared()
        assertEquals("app: hook: a take-ended clear keeps the flag cleared", false, ctrl.micBusy.get())
        ctrl.disarmCleared()
        assertEquals("app: hook: a disarm clear keeps the flag cleared", false, ctrl.micBusy.get())
    }
}
