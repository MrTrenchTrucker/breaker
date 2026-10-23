package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MUTANT M10 - one listener that throws cannot stop the others.
 *
 * `ArmedSwitch.notifySwitchListeners` wraps each listener in its own try/catch, so a listener that throws
 * is swallowed and the loop goes on. M10 removes the per-listener try/catch: the first throw then leaves
 * `notifySwitchListeners` and every listener after it is never told.
 *
 * WHAT THIS FILE PINS (behaviour, not a source scan): two listeners on one switch, the first throwing, and
 * the assert observes the SECOND one being told. The flip is driven through a real controller, whose own
 * observer call swallows the escaping exception, so the failure is a clean assertion and not an error.
 *
 * WHY THE ROW CANNOT PASS ON M10: the assert reads `told`, which is filled by the second listener - the
 * exact listener M10 never reaches.
 */
internal class ArmedSwitchThrowingListenerTest {

    private val store = MemoryOffStore()

    /** Two listeners: the first throws, the second is still told. */
    @Test
    fun `a listener that throws does not stop the others`() {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, store)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val told = ArrayList<Boolean>()
        switch.addSwitchListener { throw IllegalStateException("the first listener broke") }
        switch.addSwitchListener { armed -> told.add(armed) }
        switch.switchOn()
        assertEquals(
            "app: m10: a listener that throws must not stop the others",
            listOf(true),
            told,
        )
    }
}
