package dev.breaker.dictation.wiring

import dev.breaker.dictation.settings.ModelCatalog
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size a speech model shows on its button and its row is the size its download sentence shows: both are
 * the decimal megabytes Android shows, converted once from the registry's mebibytes.
 * Hand-traced only: nothing here has been compiled or run.
 */
internal class ModelChoiceSizeUnitsTest {
    /** The number the download sentence for [sizeMb] mebibytes shows, read out of its "(N MB)" part. */
    private fun sentenceMb(sizeMb: Int): Int =
        ModelSentences.downloadingFor(sizeMb).substringAfter("(").substringBefore(" MB)").toInt()

    @Test
    fun `each offered speech model shows the size its download sentence shows`() {
        val offered = ModelCatalog.offered()
        assertTrue("app: no offered speech model was listed", offered.isNotEmpty())
        val choices = offeredSpeechModels().associateBy { it.id }
        for (model in offered) {
            val choice = choices.getValue(model.id)
            assertEquals("app: ${model.id} button size must equal its sentence size", sentenceMb(model.sizeMb), choice.sizeMb)
        }
    }

    @Test
    fun `the small model shows 366 MB and the parakeet model shows 488 MB`() {
        assertEquals("app: the registry size of small", 349, ModelRegistry.SMALL.sizeMb)
        assertEquals("app: the registry size of parakeet", 465, ModelRegistry.PARAKEET.sizeMb)
        val choices = offeredSpeechModels().associateBy { it.id }
        assertEquals("app: small button size", 366, choices.getValue("small").sizeMb)
        assertEquals("app: parakeet button size", 488, choices.getValue("parakeet").sizeMb)
    }
}
