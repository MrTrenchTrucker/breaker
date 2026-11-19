package dev.breaker.dictation.settings

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offered list of the catalog, checked against the registry's own offer flags.
 *
 * The model ids are written here as literals, so a filter that lets an unoffered model through,
 * or drops an offered one, costs the test.
 */
class ModelCatalogOfferedFilterTest {

    @Test
    fun `offered catalog drops every model the registry does not offer`() {
        val ids = ModelCatalog.offered().map { it.id }
        assertFalse("tiny is not offered", ids.contains("tiny"))
        assertFalse("base is not offered", ids.contains("base"))
        assertFalse("medium is not offered", ids.contains("medium"))
    }

    @Test
    fun `offered catalog keeps every model the registry offers`() {
        val ids = ModelCatalog.offered().map { it.id }
        assertTrue("small is offered", ids.contains("small"))
        assertTrue("parakeet is offered", ids.contains("parakeet"))
    }

    @Test
    fun `offered catalog holds as many models as the registry marks offered`() {
        assertEquals(ModelRegistry.ALL.count { it.offered }, ModelCatalog.offered().size)
    }
}
