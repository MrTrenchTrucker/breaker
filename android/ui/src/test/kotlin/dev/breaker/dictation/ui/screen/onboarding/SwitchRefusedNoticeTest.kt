package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A switch-on refused from outside the walk-through (the launcher icon) is drawn with the same notice the
 * switch button draws: the app's sentence as given, or the fixed switch-failed notice when the sentence is
 * blank. The refusal is pinned in process memory: every drawing that is not a tap draws it again, until a tap
 * clears it or a read finds the switch on. A refusal asks the switch for nothing.
 */
class SwitchRefusedNoticeTest {
    private val sentence = "Android did not let Breaker start listening. Open Breaker and try again."

    private class Fixture {
        val switch = FakeBreakerSwitch(false)
        val handler = SetupIntentHandler(
            FakeSetupPlatform(PlatformStatus(overlay = false, microphone = true, notifications = false, accessibility = false, sdkInt = 36)),
            switch,
            { false },
            { null },
            { 0 },
            { },
            SetupScreen(),
        )
    }

    private fun Screen.notice(): String? =
        (nodes.flatMap { it.flatten() }.firstOrNull { it.id == SETUP_NOTICE_ID } as? Label)?.text

    @Test
    fun `a refusal from outside is drawn with the app's sentence as given`() {
        val f = Fixture()
        assertEquals("app: the refusal must be drawn exactly as given", sentence, f.handler.switchRefused(sentence).notice())
    }

    @Test
    fun `a blank refusal from outside is drawn with the fixed switch failed notice`() {
        for (blank in listOf("", "   ")) {
            val f = Fixture()
            assertEquals("'$blank'", SetupTexts.NOTICE_SWITCH_FAILED, f.handler.switchRefused(blank).notice())
        }
    }

    @Test
    fun `a refusal from outside asks the switch for nothing`() {
        val f = Fixture()
        f.handler.switchRefused(sentence)
        assertEquals("app: the refusal must not ask the switch to switch on", 0, f.switch.switchOnCalls)
    }

    @Test
    fun `the refusal is drawn again on each later drawing that is not a tap`() {
        val f = Fixture()
        f.handler.switchRefused(sentence)
        assertEquals("app: the attachment drawing must show the refusal", sentence, f.handler.current().notice())
        assertEquals("app: the focus drawing must show the refusal", sentence, f.handler.current().notice())
        assertEquals("app: the refresh drawing must show the refusal", sentence, f.handler.current().notice())
    }

    @Test
    fun `any intent clears the pinned refusal, and its own drawing does not show it`() {
        for (intent in listOf<ScreenIntent>(ScreenIntent.ToggleTheme, ScreenIntent.Setup(SetupActions.RECHECK))) {
            val f = Fixture()
            f.handler.switchRefused(sentence)
            val drawn = f.handler.handle(intent)
            assertNotEquals("app: the tap's own drawing must not show the pinned refusal for $intent", sentence, drawn.notice())
            assertNull("app: a tap must clear the pinned refusal for $intent", f.handler.current().notice())
        }
    }

    @Test
    fun `a read that finds the switch on clears the pinned refusal`() {
        val f = Fixture()
        f.handler.switchRefused(sentence)
        f.switch.on = true
        assertNull("app: a read that finds the switch on must clear the refusal", f.handler.current().notice())
        assertNull("app: the cleared refusal must not come back", f.handler.current().notice())
    }

    @Test
    fun `a failed read while pinned still shows the pinned refusal, not the could not check notice`() {
        val f = Fixture()
        f.handler.switchRefused(sentence)
        f.switch.failIsOn = true
        assertEquals("app: the pinned refusal must win over the could not check notice", sentence, f.handler.current().notice())
    }

    @Test
    fun `a tap's switch failure is drawn once and is not pinned`() {
        val f = Fixture()
        f.switch.failSwitchOn = true
        val tapped = f.handler.handle(ScreenIntent.Setup(SetupActions.SWITCH_ON))
        assertEquals("app: the tap's failure is drawn", SetupTexts.NOTICE_SWITCH_FAILED, tapped.notice())
        assertNull("app: the tap's failure must not be pinned", f.handler.current().notice())
    }

    @Test
    fun `the view answers the member by drawing the refusal the handler gives`() {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/render/SetupHostView.kt")
        assertTrue("the view source was not found at ${file.absolutePath}", file.isFile)
        assertTrue(
            "app: the view must draw the refusal through the handler",
            file.readText().contains("override fun showSwitchRefused(sentence: String) {\n        show(handler.switchRefused(sentence))\n    }"),
        )
    }
}
