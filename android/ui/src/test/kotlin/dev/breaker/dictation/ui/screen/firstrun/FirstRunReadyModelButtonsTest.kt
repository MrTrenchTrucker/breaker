package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.screen.onboarding.FirstRunModel
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run download page draws a button only for an offered model that is not on the phone yet.
 *
 * The choice is a pure function over the model list, so it runs on a plain JVM. The page itself draws
 * Android views and cannot run here, so its use of the choice is pinned as text in the page's own source.
 */
class FirstRunReadyModelButtonsTest {
    private val readySmall = FirstRunModel(id = "small", label = "Good", rating = 1, sizeMb = 349, ready = true)
    private val notReadySmall = readySmall.copy(ready = false)
    private val notReadyParakeet = FirstRunModel(id = "parakeet", label = "Better", rating = 2, sizeMb = 465, ready = false)

    @Test
    fun `a ready model gets no download button`() {
        assertTrue(FirstRunWords.modelsToDownload(listOf(readySmall)).isEmpty())
    }

    @Test
    fun `a model not on the phone gets one download button`() {
        assertEquals(listOf("parakeet"), FirstRunWords.modelsToDownload(listOf(notReadyParakeet)).map { it.id })
    }

    @Test
    fun `every model not on the phone gets its own button in offered order`() {
        val ids = FirstRunWords.modelsToDownload(listOf(notReadySmall, notReadyParakeet)).map { it.id }
        assertEquals(listOf("small", "parakeet"), ids)
    }

    @Test
    fun `the first model not on the phone comes first in the download list`() {
        val ids = FirstRunWords.modelsToDownload(listOf(readySmall, notReadyParakeet)).map { it.id }
        assertEquals(listOf("parakeet"), ids)
    }

    @Test
    fun `with every offered model ready the page shows the done word`() {
        assertTrue(FirstRunWords.modelsToDownload(listOf(readySmall)).isEmpty())
        assertEquals(SetupTexts.STATUS_GRANTED, FirstRunWords.modelsReady())
    }

    @Test
    fun `the first-run page draws its buttons only from the models not on the phone`() {
        val page = mainSourceOf("render/FirstRunDownloadPage.kt")
        assertTrue(
            "the page does not choose its buttons with the download list",
            page.contains("val toDownload = FirstRunWords.modelsToDownload(models)"),
        )
        assertFalse(
            "the page still draws one button for every offered model",
            page.contains("models.forEachIndexed"),
        )
    }

    @Test
    fun `the first-run page shows the done word when no model is left to download`() {
        val page = mainSourceOf("render/FirstRunDownloadPage.kt")
        assertTrue(
            "the page does not show the done word when every model is ready",
            page.contains("if (toDownload.isEmpty()) page.addView(mutedText(FirstRunWords.modelsReady()))"),
        )
    }

    @Test
    fun `the first-run page tags only the first button it draws with the download tag`() {
        val page = mainSourceOf("render/FirstRunDownloadPage.kt")
        assertTrue(
            "the page does not tag the first of its buttons",
            page.contains("if (index == 0) button.tag = FirstRunLayout.DOWNLOAD_BUTTON_TAG"),
        )
        assertTrue(
            "the tagged button is not the first of the download list",
            page.contains("toDownload.forEachIndexed { index, model ->"),
        )
    }
}
