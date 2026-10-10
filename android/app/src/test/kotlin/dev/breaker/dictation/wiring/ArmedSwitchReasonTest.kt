package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.DisarmReason
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/** Protects the reason a switch-off gives the controller: it is the user's own word. */
internal class ArmedSwitchReasonTest {
    @Test
    fun `switching off while on tells the controller observer the user's word first`() {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        val seen: MutableList<Pair<Boolean, DisarmReason?>> = ArrayList()
        controller.setStateObserver { armed, reason ->
            seen.add(Pair(armed, reason))
            switch.onStateChanged(armed, reason)
        }
        switch.switchOn()
        seen.clear()
        switch.switchOff()
        assertEquals(
            "app: the first observer call of a switch-off should be off for the user's word",
            Pair<Boolean, DisarmReason?>(false, DisarmReason.USER_WORD),
            seen.first(),
        )
        assertEquals("app: a switch-off from on should reach the observer once", 1, seen.size)
    }
}
