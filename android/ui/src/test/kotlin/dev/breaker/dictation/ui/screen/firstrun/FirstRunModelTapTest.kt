package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.FirstRunModel
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first-run controller's model tap: a model id reaches the app's id callback once, a blank id reaches nothing,
 * a second tap while a download runs is refused, and the models the page offers are the ones the provider gives.
 */
class FirstRunModelTapTest {
    private val small = FirstRunModel(id = "small", label = "Good", rating = 1, sizeMb = 349, ready = false)

    private class Fixture(models: List<FirstRunModel>) {
        val asked = mutableListOf<String>()
        val handler = SetupIntentHandler(
            platform = FakeSetupPlatform(
                PlatformStatus(overlay = false, microphone = false, notifications = false, accessibility = false, sdkInt = 36),
            ),
            switch = FakeBreakerSwitch(false),
            modelReady = { false },
            downloadLine = { null },
            modelDownloadMb = { 0 },
            onDownloadModel = { },
            screen = SetupScreen(),
        )
        val controller = FirstRunController(
            handler = handler,
            modelReady = { false },
            downloadLine = { null },
            onDownloadModel = { },
            onFinished = { },
            onDownloadModelId = { id -> asked.add(id) },
            models = { models },
        )
    }

    @Test
    fun `a model tap hands its id to the app once and a second tap while the download runs is refused`() {
        val f = Fixture(listOf())
        f.controller.tapModel("parakeet")
        f.controller.tapModel("small")
        assertEquals(listOf("parakeet"), f.asked)
    }

    @Test
    fun `a blank model id hands nothing to the app`() {
        val f = Fixture(listOf())
        f.controller.tapModel("")
        assertEquals(emptyList<String>(), f.asked)
    }

    @Test
    fun `the shown download page offers the models the provider gives in its order`() {
        val f = Fixture(listOf(small))
        assertEquals(listOf(small), f.controller.shown().models)
    }
}
