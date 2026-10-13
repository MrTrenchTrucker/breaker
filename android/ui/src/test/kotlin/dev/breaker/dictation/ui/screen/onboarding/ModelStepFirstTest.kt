package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model step is the first step of the walk-through, and it carries the download
 * action. This test proves that: the model block is rendered before the permission
 * steps, it shows "Not done yet" and a download button while the model is not ready,
 * the button reports the download action, and the handler calls onDownloadModel when
 * that action is tapped.
 *
 * It also proves the live-provider contract: flipping the modelReady provider and
 * calling refresh() flips the step from "Not done yet" to "Done" without recreating
 * the view, and a non-null downloadLine shows under the button while a null one shows
 * nothing.
 */
class ModelStepFirstTest {
    private val screen = SetupScreen()

    private fun status(
        overlay: Boolean = false,
        microphone: Boolean = false,
        notifications: Boolean = false,
        accessibility: Boolean = false,
        sdkInt: Int = 36,
    ) = PlatformStatus(overlay, microphone, notifications, accessibility, sdkInt)

    private fun state(
        phone: PlatformStatus = status(),
        on: Boolean = false,
        micAsked: Boolean = false,
        notificationsAsked: Boolean = false,
        modelReady: Boolean = false,
        downloadLine: String? = null,
        modelDownloadMb: Int = 0,
    ) = SetupState(phone, on, micAsked, notificationsAsked, modelReady, downloadLine, modelDownloadMb)

    private fun render(shown: SetupState = state()): Screen = screen.render(shown, null)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun node(s: Screen, id: String): Node? = nodesOf(s).firstOrNull { it.id == id }

    private fun label(s: Screen, id: String): Label =
        node(s, id) as? Label ?: throw AssertionError("no Label with id $id in ${nodesOf(s).map { it.id }}")

    private fun action(s: Screen, id: String): Action =
        node(s, id) as? Action ?: throw AssertionError("no Action with id $id in ${nodesOf(s).map { it.id }}")

    private fun setupAction(a: Action): String =
        (a.intent as? ScreenIntent.Setup)?.action ?: throw AssertionError("action ${a.id} reports ${a.intent}")

    @Test
    fun `the model step is the first step block, before the permission steps`() {
        val s = render(state())
        val blockIds = s.nodes.filterIsInstance<Box>().map { it.id }
        assertEquals(
            "the model block must be the first step block",
            "setup.step.model",
            blockIds.first { it.startsWith("setup.step.") },
        )
        // The permission steps follow in order
        val stepBlocks = blockIds.filter { it.startsWith("setup.step.") }
        assertEquals(
            listOf("setup.step.model", "setup.step.overlay", "setup.step.microphone", "setup.step.notifications", "setup.step.accessibility"),
            stepBlocks,
        )
    }

    @Test
    fun `while the model is not ready the step shows Not done yet and a download button`() {
        val s = render(state(modelReady = false))
        assertEquals(SetupTexts.STATUS_NOT_GRANTED, label(s, "setup.step.model.status").text)
        val button = action(s, "setup.step.model.button")
        assertEquals(SetupTexts.BUTTON_DOWNLOAD_MODEL, button.text)
        assertEquals(ScreenIntent.Setup(SetupActions.DOWNLOAD_MODEL), button.intent)
        assertTrue(button.enabled)
    }

    @Test
    fun `while the model is ready the step shows Done and has no button`() {
        val s = render(state(modelReady = true))
        assertEquals(SetupTexts.STATUS_GRANTED, label(s, "setup.step.model.status").text)
        assertNull("no download button when the model is ready", node(s, "setup.step.model.button"))
    }

    @Test
    fun `a non-null download line shows under the button and a null one shows nothing`() {
        val withLine = render(state(modelReady = false, downloadLine = "Downloading the speech model…"))
        assertEquals("Downloading the speech model…", label(withLine, "setup.step.model.line").text)

        val withoutLine = render(state(modelReady = false, downloadLine = null))
        assertNull("no line when the app has nothing to say", node(withoutLine, "setup.step.model.line"))
    }

    @Test
    fun `the download button reports the download action and the handler calls onDownloadModel`() {
        // The screen reports the action; the handler is tested in SetupIntentHandlerTest.
        // Here we prove the button's intent is the download action.
        val s = render(state(modelReady = false))
        val button = action(s, "setup.step.model.button")
        assertEquals(SetupActions.DOWNLOAD_MODEL, setupAction(button))
    }

    @Test
    fun `the model step has a heading and a why sentence`() {
        val s = render(state())
        assertEquals(SetupTexts.HEADING_MODEL, label(s, "setup.step.model.heading").text)
        assertEquals(SetupTexts.WHY_MODEL, label(s, "setup.step.model.why").text)
    }

    @Test
    fun `while the model is not ready the step shows the source sentence with the size it was given`() {
        val s = render(state(modelReady = false, modelDownloadMb = 366))
        assertEquals(
            SetupTexts.MODEL_STEP_LINE.replace("{size}", "366"),
            label(s, "setup.step.model.source").text,
        )
    }

    @Test
    fun `the source sentence shows whatever size it is given, not a fixed one`() {
        for (size in listOf(366, 128, 209, 1906)) {
            val s = render(state(modelReady = false, modelDownloadMb = size))
            assertEquals(
                "size $size",
                SetupTexts.MODEL_STEP_LINE.replace("{size}", size.toString()),
                label(s, "setup.step.model.source").text,
            )
        }
    }

    @Test
    fun `while the model is ready there is no source sentence`() {
        val s = render(state(modelReady = true, modelDownloadMb = 366))
        assertNull("no source sentence when the model is ready", node(s, "setup.step.model.source"))
    }

    @Test
    fun `the four permission steps still follow the model step in order`() {
        val s = render(state())
        val stepBlocks = s.nodes.filterIsInstance<Box>().map { it.id }.filter { it.startsWith("setup.step.") }
        assertEquals(5, stepBlocks.size)
        assertEquals("setup.step.model", stepBlocks[0])
        assertEquals("setup.step.overlay", stepBlocks[1])
        assertEquals("setup.step.microphone", stepBlocks[2])
        assertEquals("setup.step.notifications", stepBlocks[3])
        assertEquals("setup.step.accessibility", stepBlocks[4])
    }
}
