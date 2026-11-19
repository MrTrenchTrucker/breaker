package dev.breaker.dictation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The parakeet model as a settings value: its id is accepted on the read path and on the write path,
 * an unknown id is still refused on both, and the offered list is small then parakeet, by id and order.
 *
 * The ids are written here as literals, so a registry change that drops parakeet costs the test.
 */
class SettingsParakeetModelTest {

    @Test
    fun `the read path keeps the parakeet id as the model size`() {
        assertEquals("parakeet", SettingsValidation.modelSizeOrFallback("parakeet", "small"))
    }

    @Test
    fun `the write path accepts the parakeet id without a refusal`() {
        SettingsValidation.requireRegistryModelSize("parakeet")
    }

    @Test
    fun `the read path still falls an unknown id back to the default`() {
        assertEquals("small", SettingsValidation.modelSizeOrFallback("giant", "small"))
    }

    @Test
    fun `the write path still refuses an unknown id`() {
        val thrown = try {
            SettingsValidation.requireRegistryModelSize("giant")
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }
        assertNotNull("an unknown id was not refused", thrown)
    }

    @Test
    fun `offered lists small and then parakeet by id and in that order`() {
        assertEquals(listOf("small", "parakeet"), ModelCatalog.offered().map { it.id })
    }
}
