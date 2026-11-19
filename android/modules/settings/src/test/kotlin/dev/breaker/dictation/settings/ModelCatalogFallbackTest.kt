package dev.breaker.dictation.settings

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The model a phone shows when the saved model id is not one the registry carries: the registry's
 * default entry, found by its default flag, never an entry named in code. Every expected value is
 * asked of the registry.
 */
class ModelCatalogFallbackTest {

    private val registryDefault = ModelRegistry.ALL.single { it.isDefault }

    @Test
    fun `an id the registry does not carry falls back to the default entry's size`() {
        assertEquals(registryDefault.sizeMb, ModelCatalog.byIdOrDefault("giant").sizeMb)
        assertEquals(registryDefault.id, ModelCatalog.byIdOrDefault("giant").id)
    }

    @Test
    fun `the default accessor answers the entry flagged as default`() {
        assertEquals(registryDefault.id, ModelCatalog.default().id)
        assertEquals(registryDefault.sha256, ModelCatalog.default().sha256)
    }

    @Test
    fun `an id the registry carries is answered as itself, not as the default`() {
        val other = ModelRegistry.ALL.first { !it.isDefault }
        assertEquals(other.id, ModelCatalog.byIdOrDefault(other.id).id)
        assertNotEquals(registryDefault.id, ModelCatalog.byIdOrDefault(other.id).id)
    }
}
