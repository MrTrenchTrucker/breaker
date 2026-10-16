package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.OpenResult
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupNotice
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.screen.onboarding.SetupStep
import dev.breaker.dictation.ui.screen.onboarding.StepStatus
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The page order, the steps, the missing list, the taps and the refresh of the first-run
 * controller, over the existing fakes.
 */
class FirstRunControllerFlowTest {
    private val nothing = PlatformStatus(
        overlay = false,
        microphone = false,
        notifications = false,
        accessibility = false,
        sdkInt = 36,
    )

    private val walk = listOf(
        FirstRunPage.WELCOME,
        FirstRunPage.MICROPHONE,
        FirstRunPage.OVERLAY,
        FirstRunPage.NOTIFICATIONS,
        FirstRunPage.ACCESSIBILITY,
        FirstRunPage.DOWNLOAD,
    )

    /** A controller over the fakes. The model and the download sentence are plain fields. */
    private class Rig(phone: PlatformStatus) {
        val platform = FakeSetupPlatform(phone)
        val switch = FakeBreakerSwitch(false)
        var modelReady = false
        var downloads = 0
        var finished = 0
        val handler = SetupIntentHandler(platform, switch, { false }, { null }, { 0 }, { }, SetupScreen())
        val controller = FirstRunController(
            handler = handler,
            modelReady = { modelReady },
            downloadLine = { null },
            onDownloadModel = { downloads++ },
            onFinished = { finished++ },
        )
    }

    @Test
    fun `a new controller is on the welcome page and has read the phone once`() {
        val r = Rig(nothing)
        assertEquals(FirstRunPage.WELCOME, r.controller.page)
        assertEquals(FirstRunPage.WELCOME, r.controller.shown().page)
        assertEquals(1, r.platform.statusCalls)
    }

    @Test
    fun `advance walks the order and stops on the download page, and advance there changes nothing`() {
        val r = Rig(nothing)
        val seen = mutableListOf(r.controller.page)
        repeat(5) {
            r.controller.advance()
            seen += r.controller.page
        }
        assertEquals(walk, seen)
        r.controller.advance()
        assertEquals(FirstRunPage.DOWNLOAD, r.controller.page)
        assertEquals("the download page asks the phone nothing", 6, r.platform.statusCalls)
    }

    @Test
    fun `shown step is 0 1 2 3 4 0 along the walk`() {
        val r = Rig(nothing)
        val steps = mutableListOf(r.controller.shown().step)
        repeat(5) {
            r.controller.advance()
            steps += r.controller.shown().step
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 0), steps)
    }

    @Test
    fun `shown missing is the missing list for the phone's answers and advance refreshes it`() {
        val r = Rig(nothing)
        assertEquals(FirstRunMissing.of(r.controller.shown().state), r.controller.shown().missing)
        assertEquals(
            listOf(FirstRunPage.MICROPHONE, FirstRunPage.OVERLAY, FirstRunPage.NOTIFICATIONS, FirstRunPage.ACCESSIBILITY),
            r.controller.shown().missing.map { it.page },
        )
        r.platform.answer = nothing.copy(microphone = true)
        r.controller.advance()
        assertEquals(FirstRunMissing.of(r.controller.shown().state), r.controller.shown().missing)
        assertEquals(
            listOf(FirstRunPage.OVERLAY, FirstRunPage.NOTIFICATIONS, FirstRunPage.ACCESSIBILITY),
            r.controller.shown().missing.map { it.page },
        )
    }

    @Test
    fun `a microphone tap with nothing granted asks the platform for the prompt`() {
        val r = Rig(nothing)
        r.controller.tap("open.microphone")
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE), r.platform.opened)
    }

    @Test
    fun `a second microphone tap asks for the app info page once the prompt was asked`() {
        val r = Rig(nothing)
        r.controller.tap("open.microphone")
        r.controller.tap("open.microphone")
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.APP_INFO), r.platform.opened)
    }

    @Test
    fun `a tap the phone refuses gives the could not open text`() {
        val r = Rig(nothing)
        r.platform.openResult = OpenResult.NOT_POSSIBLE
        r.controller.tap("open.overlay")
        assertEquals(SetupNotice.COULD_NOT_OPEN.text, r.controller.shown().notice)
    }

    @Test
    fun `an unknown action gives the not accepted text and asks nothing`() {
        val r = Rig(nothing)
        r.controller.tap("open.unknown")
        assertEquals(SetupNotice.NOT_ACCEPTED.text, r.controller.shown().notice)
        assertEquals(emptyList<OpenAction>(), r.platform.opened)
    }

    @Test
    fun `refresh re-reads the phone, so a permission granted after construction shows after refresh`() {
        val r = Rig(nothing)
        r.platform.answer = nothing.copy(microphone = true)
        assertFalse(r.controller.shown().state.status.microphone)
        r.controller.refresh()
        assertEquals(StepStatus.GRANTED, r.controller.shown().state.statusOf(SetupStep.MICROPHONE))
        assertFalse(r.controller.shown().missing.any { it.page == FirstRunPage.MICROPHONE })
    }
}
