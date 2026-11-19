package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The onboarding model step draws a download button only for an offered model that is not on the phone yet.
 * When every offered model is already there, the step says the one existing done word and draws no button.
 */
class SetupReadyModelButtonsTest {
    private val screen = SetupScreen()

    private val readySmall = FirstRunModel(id = "small", label = "Good", rating = 1, sizeMb = 349, ready = true)
    private val notReadyParakeet = FirstRunModel(id = "parakeet", label = "Better", rating = 2, sizeMb = 465, ready = false)
    private val readyParakeet = notReadyParakeet.copy(ready = true)

    private fun state(models: List<FirstRunModel>) = SetupState(
        status = PlatformStatus(overlay = false, microphone = false, notifications = false, accessibility = false, sdkInt = 36),
        switchedOn = false,
        micAskedBefore = false,
        models = models,
    )

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun modelButtons(s: Screen): List<Action> =
        nodesOf(s).filter { it.id.startsWith("setup.step.model.button") }.map { it as Action }

    @Test
    fun `a ready model gets no download button on the onboarding step`() {
        val buttons = modelButtons(screen.render(state(listOf(readySmall, notReadyParakeet)), null))
        assertEquals(listOf(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "parakeet")), buttons.map { it.intent })
    }

    @Test
    fun `the one model not on the phone keeps the step's own button id`() {
        val buttons = modelButtons(screen.render(state(listOf(readySmall, notReadyParakeet)), null))
        assertEquals(listOf("setup.step.model.button"), buttons.map { it.id })
    }

    @Test
    fun `with every offered model ready the onboarding step shows the done word and no button`() {
        val s = screen.render(state(listOf(readySmall, readyParakeet)), null)
        assertEquals(emptyList<Action>(), modelButtons(s))
        val done = nodesOf(s).firstOrNull { it.id == "setup.step.model.ready" } as? Label
        assertEquals(SetupTexts.STATUS_GRANTED, done?.text)
    }
}
