package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MUTANT M7 - the switch listeners are told only once the off-store is settled.
 *
 * `ArmedSwitch.onStateChanged` writes the store first and tells the listeners last, so a listener that
 * reads the store when it is told sees the value the change just wrote. M7 moves the tell BEFORE the
 * write: the listener then reads the value the change had not written yet.
 *
 * WHAT THIS FILE PINS (behaviour, not a source scan): a listener that reads the [OffStore] inside its own
 * callback. An arm clears the stored off choice, so the listener must read not-off; a USER_WORD disarm
 * stores it, so the listener must read off. Both rows are driven on the real chain - a real
 * [DictationServiceController] whose one observer is the switch, exactly as the app registers it.
 *
 * The store is put into the state the change flips BEFORE the flip, so the reading can only be right if
 * the write has already happened: on the arm the store starts off, on the disarm it starts not-off.
 *
 * WHY THE ROWS CANNOT PASS ON M7: the assert reads `store.isOff()` inside the callback, which is the very
 * value M7 leaves stale - the arm row would read off, the disarm row would read not-off.
 */
internal class ArmedSwitchListenerSeesSettledStoreTest {

    private val store = MemoryOffStore()

    /** Reads the store the moment it is told; the reading is the listener's own view of the settled state. */
    private class StoreReadingSwitchListener(private val store: OffStore, private val seen: MutableList<Boolean>) {
        operator fun invoke(armed: Boolean) {
            seen.add(store.isOff())
        }
    }

    /** The switch over a real controller, wired the way the app wires it: the switch is the controller's observer. */
    private fun built(): ArmedSwitch {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, store)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return switch
    }

    /** An arm tells the listener with the off choice already cleared. */
    @Test
    fun `an arm tells the listener after the off choice is cleared`() {
        store.off = true
        val seen = ArrayList<Boolean>()
        val switch = built()
        switch.addSwitchListener(StoreReadingSwitchListener(store, seen)::invoke)
        switch.switchOn()
        assertEquals(
            "app: m7: the arm must tell the listener after the store is cleared, so it reads not-off",
            listOf(false),
            seen,
        )
    }

    /**
     * A USER_WORD disarm tells the listener with the off choice already stored. `switchOff` reaches
     * `onStateChanged` twice (the controller's disarm notifies the observer, then `switchOff` calls it
     * again), so the listener is told twice and every reading must be the settled one.
     */
    @Test
    fun `a user disarm tells the listener after the off choice is stored`() {
        val seen = ArrayList<Boolean>()
        val switch = built()
        switch.addSwitchListener(StoreReadingSwitchListener(store, seen)::invoke)
        switch.switchOn()
        seen.clear()
        switch.switchOff()
        assertEquals(
            "app: m7: the disarm must tell the listener after the store is written, so it reads off",
            listOf(true, true),
            seen,
        )
    }
}
