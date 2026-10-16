package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.BreakerSwitch
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [SetupIntentHandler.lastState] returns: the nothing-known state before any drawing, the
 * state the last drawing used, and the state a tap leaves behind. Reading it asks nothing.
 */
class SetupIntentHandlerLastStateTest {
    private val nothing = PlatformStatus(
        overlay = false,
        microphone = false,
        notifications = false,
        accessibility = false,
        sdkInt = 36,
    )

    private class Fixture(phone: PlatformStatus, on: Boolean = false) {
        val platform = FakeSetupPlatform(phone)
        val switch = FakeBreakerSwitch(on)
        val handler = SetupIntentHandler(
            platform,
            switch,
            { false },
            { null },
            { 0 },
            { },
            SetupScreen(),
        )
    }

    private fun Screen.node(id: String): Node? = nodes.flatMap { it.flatten() }.firstOrNull { it.id == id }

    private fun Screen.text(id: String): String? = when (val n = node(id)) {
        is Label -> n.text
        is Action -> n.text
        else -> null
    }

    @Test
    fun `before anything is drawn the last state is the nothing-known state and nothing is asked`() {
        val f = Fixture(nothing.copy(microphone = true), on = true)
        val expected = SetupState(
            status = PlatformStatus(
                overlay = false,
                microphone = false,
                notifications = false,
                accessibility = false,
                sdkInt = 0,
            ),
            switchedOn = false,
            micAskedBefore = false,
        )
        assertEquals(expected, f.handler.lastState())
        assertEquals("the phone was asked", 0, f.platform.statusCalls)
        assertEquals("the switch was asked", 0, f.switch.isOnCalls)
    }

    @Test
    fun `after a drawing the last state is the one that drawing used`() {
        val f = Fixture(nothing.copy(microphone = true), on = true)
        val screen = f.handler.current()
        val state = f.handler.lastState()
        assertEquals(true, state.status.microphone)
        assertEquals(true, state.switchedOn)
        assertEquals(StepStatus.GRANTED, state.statusOf(SetupStep.MICROPHONE))
        assertEquals(SetupTexts.STATUS_GRANTED, screen.text("setup.step.microphone.status"))
        assertEquals(SetupTexts.SWITCH_ON_STATE, screen.text("setup.switch.state"))
    }

    @Test
    fun `a later drawing replaces the last state with what the phone says now`() {
        val f = Fixture(nothing)
        f.handler.current()
        f.platform.answer = nothing.copy(overlay = true)
        f.handler.current()
        assertEquals(true, f.handler.lastState().status.overlay)
    }

    @Test
    fun `after a tap the last state follows the tap`() {
        val f = Fixture(nothing.copy(microphone = true))
        f.switch.nextResult = BreakerSwitch.Result.On
        f.handler.current()
        assertEquals(false, f.handler.lastState().switchedOn)
        f.handler.handle(ScreenIntent.Setup("switch.on"))
        assertEquals(true, f.handler.lastState().switchedOn)
    }

    @Test
    fun `a request the handler refuses leaves the last state as the drawing before it`() {
        val f = Fixture(nothing.copy(microphone = true))
        f.handler.current()
        val before = f.handler.lastState()
        f.handler.handle(ScreenIntent.ToggleTheme)
        assertEquals(before, f.handler.lastState())
    }
}
