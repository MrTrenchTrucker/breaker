package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.BreakerSwitch
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SETUP_NOTICE_ID
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stand-in switch of the first run: always off, always refusing, and shown as a refusal by the set-up handler. */
class NoSwitchTest {
    private val phone = PlatformStatus(
        overlay = false,
        microphone = true,
        notifications = false,
        accessibility = false,
        sdkInt = 36,
    )

    @Test
    fun `the switch is never on`() {
        assertFalse(NoSwitch.isOn())
    }

    @Test
    fun `switching on is refused with a blank sentence`() {
        val result = NoSwitch.switchOn()
        assertTrue(result is BreakerSwitch.Result.Refused)
        assertTrue((result as BreakerSwitch.Result.Refused).sentence.isBlank())
    }

    @Test
    fun `switching off does nothing and does not throw`() {
        NoSwitch.switchOff()
        assertFalse(NoSwitch.isOn())
    }

    @Test
    fun `a tap on the switch shows the switch-failed notice and never marks the switch on`() {
        val handler = SetupIntentHandler(
            FakeSetupPlatform(phone),
            NoSwitch,
            { false },
            { null },
            { 0 },
            { },
            SetupScreen(),
        )
        val after = handler.handle(ScreenIntent.Setup("switch.on"))
        val notice = (after.nodes.flatMap { it.flatten() }.firstOrNull { it.id == SETUP_NOTICE_ID } as? Label)?.text
        assertEquals(SetupTexts.NOTICE_SWITCH_FAILED, notice)
        assertFalse(NoSwitch.isOn())
    }
}
