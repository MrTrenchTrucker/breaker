package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The set-up walk-through refuses a theme mode: the phone and the switch are not touched and the rows stay as they were. */
class SetupIntentHandlerThemeModeTest {
    private class Fixture {
        val platform = FakeSetupPlatform(
            PlatformStatus(
                overlay = false,
                microphone = true,
                notifications = false,
                accessibility = false,
                sdkInt = 36,
            ),
        )
        val switch = FakeBreakerSwitch(on = false)
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

    private fun Screen.noticeText(): String? =
        nodes.flatMap { it.flatten() }.filterIsInstance<Label>().firstOrNull { it.id == "setup.notice" }?.text

    @Test
    fun `each theme mode name is refused with the not accepted notice`() {
        val f = Fixture()
        for (name in listOf("SYSTEM", "LIGHT", "DARK")) {
            val after = f.handler.handle(ScreenIntent.SetThemeMode(name))
            assertEquals("refusal for $name", SetupTexts.NOTICE_NOT_ACCEPTED, after.noticeText())
        }
    }

    @Test
    fun `a theme mode leaves the phone the switch and the rows alone`() {
        val f = Fixture()
        val before = f.handler.current()
        val statusBefore = f.platform.statusCalls
        val switchBefore = f.switch.isOnCalls
        val after = f.handler.handle(ScreenIntent.SetThemeMode("DARK"))
        assertEquals(statusBefore, f.platform.statusCalls)
        assertEquals(switchBefore, f.switch.isOnCalls)
        assertEquals(0, f.switch.switchOnCalls + f.switch.switchOffCalls)
        assertTrue("the phone was asked to open nothing", f.platform.opened.isEmpty())
        assertEquals(
            before.nodes.filterNot { it.id == "setup.notice" },
            after.nodes.filterNot { it.id == "setup.notice" },
        )
    }
}
