package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupNotice
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A switch-on refused outside the first run is shown as the notice of the current page, pinned
 * until a tap or a read that finds the switch on. A provider that throws keeps its last answer.
 */
class FirstRunControllerSwitchTest {
    private val refusal = "Android did not let Breaker start listening."

    private val walk = listOf(
        FirstRunPage.WELCOME,
        FirstRunPage.MICROPHONE,
        FirstRunPage.OVERLAY,
        FirstRunPage.NOTIFICATIONS,
        FirstRunPage.ACCESSIBILITY,
        FirstRunPage.DOWNLOAD,
    )

    /** A controller over the fakes. The providers can be made to throw. */
    private class Rig {
        val platform = FakeSetupPlatform(
            PlatformStatus(
                overlay = false,
                microphone = true,
                notifications = false,
                accessibility = false,
                sdkInt = 36,
            ),
        )
        val switch = FakeBreakerSwitch(false)
        var modelReady = false
        var modelThrows = false
        var downloadLine: String? = null
        var lineThrows = false
        var finished = 0
        val handler = SetupIntentHandler(platform, switch, { false }, { null }, { 0 }, { }, SetupScreen())
        val controller = FirstRunController(
            handler = handler,
            modelReady = { if (modelThrows) throw IllegalStateException("model read failed") else modelReady },
            downloadLine = { if (lineThrows) throw IllegalStateException("line read failed") else downloadLine },
            onDownloadModel = { },
            onFinished = { finished++ },
        )
    }

    @Test
    fun `a refused switch-on is shown as the app's sentence on every page`() {
        for (page in walk) {
            val r = Rig()
            while (r.controller.page != page) r.controller.advance()
            r.controller.switchRefused(refusal)
            assertEquals("on $page", refusal, r.controller.shown().notice)
        }
    }

    @Test
    fun `a refused switch-on asks the switch for nothing`() {
        val r = Rig()
        r.controller.switchRefused(refusal)
        assertEquals(0, r.switch.switchOnCalls)
    }

    @Test
    fun `the refused sentence is still shown after refresh`() {
        val r = Rig()
        r.controller.switchRefused(refusal)
        r.controller.refresh()
        r.controller.refresh()
        assertEquals(refusal, r.controller.shown().notice)
    }

    @Test
    fun `a tap clears the refused sentence and it does not come back on refresh`() {
        val r = Rig()
        r.controller.switchRefused(refusal)
        r.controller.tap("recheck")
        assertNull(r.controller.shown().notice)
        r.controller.refresh()
        assertNull(r.controller.shown().notice)
    }

    @Test
    fun `a read that finds the switch on clears the refused sentence`() {
        val r = Rig()
        r.controller.switchRefused(refusal)
        r.switch.on = true
        r.controller.refresh()
        assertNull(r.controller.shown().notice)
        r.controller.refresh()
        assertNull(r.controller.shown().notice)
    }

    @Test
    fun `a blank refusal shows the switch failed text`() {
        for (blank in listOf("", "   ")) {
            val r = Rig()
            r.controller.switchRefused(blank)
            assertEquals("'$blank'", SetupNotice.SWITCH_FAILED.text, r.controller.shown().notice)
        }
    }

    @Test
    fun `a model read that throws keeps the last answer and does not throw out of refresh`() {
        val r = Rig()
        r.modelReady = true
        r.controller.advance()
        assertEquals(0, r.finished)
        r.modelThrows = true
        repeat(5) { r.controller.advance() }
        assertEquals(FirstRunPage.DOWNLOAD, r.controller.page)
        assertEquals("the kept answer finishes the first run once", 1, r.finished)
    }

    @Test
    fun `a download line read that throws keeps the last line and does not throw out of refresh`() {
        val r = Rig()
        r.downloadLine = "Fetching the model."
        r.controller.refresh()
        r.lineThrows = true
        r.controller.refresh()
        assertEquals("Fetching the model.", r.controller.shown().download.line)
    }
}
