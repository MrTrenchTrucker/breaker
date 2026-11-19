package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.FirstRunModel
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text of a speech model's button: the spoken rating, then the trucks, then the size.
 *
 * The truck emoji is written as its two UTF-16 units, so this source stays plain ASCII. A model
 * with no label or no rating gets the one download sentence, read from SetupTexts.
 */
class FirstRunModelButtonTextTest {
    private val one = "\uD83D\uDE9A"
    private val two = one + one

    private fun model(label: String?, rating: Int?) =
        FirstRunModel(id = "model", label = label, rating = rating, sizeMb = 349, ready = false)

    @Test
    fun `a good model button keeps its one truck after the spoken rating`() {
        assertTrue(FirstRunWords.modelButtonText(model("Good", 1)).contains("Good, 1 of 3 $one"))
    }

    @Test
    fun `a better model button keeps its two trucks after the spoken rating`() {
        assertTrue(FirstRunWords.modelButtonText(model("Better", 2)).contains("Better, 2 of 3 $two"))
    }

    @Test
    fun `a model with no label gets the one download sentence`() {
        assertEquals(SetupTexts.BUTTON_DOWNLOAD_MODEL, FirstRunWords.modelButtonText(model(null, 1)))
    }

    @Test
    fun `a model with no rating gets the one download sentence`() {
        assertEquals(SetupTexts.BUTTON_DOWNLOAD_MODEL, FirstRunWords.modelButtonText(model("Good", null)))
    }
}
