package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.screen.onboarding.FirstRunModel
import dev.breaker.dictation.ui.screen.settings.ModelRowItem
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * The pure mapping from an offered speech model to the settings row and to the first-run model. The entries
 * themselves need an Android context, so no JVM test builds them; these functions are what they call.
 */
class SpeechModelEntryMappingTest {
    private val small = SpeechModelChoice("small", "Good", 1, 349)
    private val parakeet = SpeechModelChoice("parakeet", "Better", 2, 465)

    @Test
    fun `an offered model becomes a settings row with nothing yet known about the phone`() {
        assertEquals(
            listOf(ModelRowItem(id = "small", label = "Good", rating = 1, sizeMb = 349, installed = false, inUse = false, line = null)),
            rowItemsOf(listOf(small)),
        )
    }

    @Test
    fun `the settings rows keep the offered order and carry each label and rating`() {
        val rows = rowItemsOf(listOf(small, parakeet))
        assertEquals(listOf("small", "parakeet"), rows.map { it.id })
        assertEquals(listOf("Good", "Better"), rows.map { it.label })
        assertEquals(listOf(1, 2), rows.map { it.rating })
    }

    @Test
    fun `no offered model gives no settings rows, so the screen keeps its one button`() {
        assertEquals(emptyList<ModelRowItem>(), rowItemsOf(emptyList()))
    }

    @Test
    fun `an offered model becomes a first-run model whose readiness is asked for its own id`() {
        val asked = mutableListOf<String>()
        val models = firstRunModelsOf(listOf(small, parakeet)) { id ->
            asked.add(id)
            id == "parakeet"
        }
        assertEquals(
            listOf(
                FirstRunModel(id = "small", label = "Good", rating = 1, sizeMb = 349, ready = false),
                FirstRunModel(id = "parakeet", label = "Better", rating = 2, sizeMb = 465, ready = true),
            ),
            models,
        )
        assertEquals(listOf("small", "parakeet"), asked)
    }

    @Test
    fun `no offered model gives no first-run models, so the step keeps its one button`() {
        assertEquals(emptyList<FirstRunModel>(), firstRunModelsOf(emptyList()) { true })
    }
}
