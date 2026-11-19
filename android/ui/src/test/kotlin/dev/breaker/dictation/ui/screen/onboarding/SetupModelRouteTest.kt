package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a model-* set-up intent does in the set-up handler: a model download with an id reaches the app's callback
 * once, a blank id or a model delete or use is refused and calls nothing, and a throw shows the download sentence.
 */
class SetupModelRouteTest {
    private class Fixture(private val failDownload: Boolean = false) {
        val platform = FakeSetupPlatform(
            PlatformStatus(overlay = false, microphone = false, notifications = false, accessibility = false, sdkInt = 36),
        )
        val switch = FakeBreakerSwitch(false)
        val asked = mutableListOf<String>()
        val handler = SetupIntentHandler(
            platform = platform,
            switch = switch,
            modelReady = { false },
            downloadLine = { null },
            modelDownloadMb = { 0 },
            onDownloadModel = { },
            screen = SetupScreen(),
            onDownloadModelId = { id ->
                if (failDownload) throw IllegalStateException("model download failed")
                asked.add(id)
            },
        )
    }

    private fun Screen.notice(): String? =
        (nodes.flatMap { it.flatten() }.firstOrNull { it.id == SETUP_NOTICE_ID } as? Label)?.text

    @Test
    fun `a model download tap with an id hands that id to the app once and draws no notice`() {
        val f = Fixture()
        val drawn = f.handler.handle(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "parakeet"))
        assertEquals(listOf("parakeet"), f.asked)
        assertNull(drawn.notice())
    }

    @Test
    fun `a model download tap with a blank id hands nothing to the app and draws the not accepted notice`() {
        val f = Fixture()
        val drawn = f.handler.handle(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, ""))
        assertEquals(emptyList<String>(), f.asked)
        assertEquals(SetupTexts.NOTICE_NOT_ACCEPTED, drawn.notice())
    }

    @Test
    fun `a model download that the app throws on draws the download sentence and no exception text`() {
        val f = Fixture(failDownload = true)
        val drawn = f.handler.handle(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "small"))
        assertEquals(SetupTexts.NOTICE_COULD_NOT_DOWNLOAD, drawn.notice())
    }

    @Test
    fun `a model delete or use tap is refused and asks the phone, the switch and the app for nothing`() {
        for (action in listOf(ModelActions.MODEL_DELETE, ModelActions.MODEL_USE)) {
            val f = Fixture()
            val drawn = f.handler.handle(ScreenIntent.Setup(action, "small"))
            assertEquals(action, SetupTexts.NOTICE_NOT_ACCEPTED, drawn.notice())
            assertEquals(action, emptyList<String>(), f.asked)
            assertEquals(action, emptyList<OpenAction>(), f.platform.opened)
            assertEquals(action, 0, f.switch.switchOnCalls + f.switch.switchOffCalls)
        }
    }
}
