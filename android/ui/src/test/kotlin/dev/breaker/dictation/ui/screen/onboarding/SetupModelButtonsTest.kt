package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model step's buttons as the set-up screen draws them: with no model offered the tree is today's, and with two
 * models there is one button per model, in order, the first keeping the step's own id, each reporting the
 * model-download action with its id, and each carrying its owner label with the rating spoken.
 */
class SetupModelButtonsTest {
    private val screen = SetupScreen()

    private val small = FirstRunModel(id = "small", label = "Good", rating = 1, sizeMb = 349, ready = false)
    private val parakeet = FirstRunModel(id = "parakeet", label = "Better", rating = 2, sizeMb = 465, ready = false)

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
    fun `with no model offered the model step draws the one download button as before`() {
        val s = screen.render(state(emptyList()), null)
        val buttons = modelButtons(s)
        assertEquals(1, buttons.size)
        assertEquals("setup.step.model.button", buttons[0].id)
        assertEquals(SetupTexts.BUTTON_DOWNLOAD_MODEL, buttons[0].text)
        assertEquals(ScreenIntent.Setup(SetupActions.DOWNLOAD_MODEL), buttons[0].intent)
    }

    @Test
    fun `with no model offered the screen keeps today's block order`() {
        val ids = screen.render(state(emptyList()), null).nodes.map { it.id }
        assertEquals(
            listOf(
                "setup.title",
                "setup.stripe",
                "setup.intro",
                "setup.step.model",
                "setup.step.overlay",
                "setup.step.microphone",
                "setup.step.notifications",
                "setup.step.accessibility",
                "setup.switch",
                "setup.recheck",
            ),
            ids,
        )
    }

    @Test
    fun `with two models the step draws two model buttons in order and the first keeps the step's own id`() {
        val buttons = modelButtons(screen.render(state(listOf(small, parakeet)), null))
        assertEquals(listOf("setup.step.model.button", "setup.step.model.button.parakeet"), buttons.map { it.id })
        assertEquals(
            listOf(
                ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "small"),
                ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "parakeet"),
            ),
            buttons.map { it.intent },
        )
    }

    @Test
    fun `each model button carries its owner label with the rating spoken and its trucks`() {
        val texts = modelButtons(screen.render(state(listOf(small, parakeet)), null)).map { it.text }
        assertEquals(2, texts.size)
        assertTrue("first button: ${texts[0]}", texts[0].contains("Good, 1 of 3"))
        assertTrue("second button: ${texts[1]}", texts[1].contains("Better, 2 of 3"))
    }

    @Test
    fun `with models offered the step draws no source sentence of its own`() {
        val s = screen.render(state(listOf(small, parakeet)), null)
        assertNull(nodesOf(s).firstOrNull { it.id == "setup.step.model.source" })
    }
}
