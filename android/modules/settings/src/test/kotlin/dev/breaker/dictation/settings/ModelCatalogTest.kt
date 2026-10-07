package dev.breaker.dictation.settings

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the model list answers, pinned against the registry's own constants.
 *
 * Every expected value here is asked of the registry, never re-typed: when a
 * registry constant moves, the pin moves with it, and a projection that swaps
 * two fields or drops an entry still costs the test — this file pins the
 * fidelity of the projection, and the registry's own content is pinned by the
 * registry's own tests.
 */
class ModelCatalogTest {

    /** One entry per registry model, in the registry's order, field for field. */
    @Test
    fun `all lists every model the registry describes in registry order`() {
        val listed = ModelCatalog.all()

        assertEquals("one entry per registry model, no more", ModelRegistry.ALL.size, listed.size)
        listed.forEachIndexed { index, info ->
            val entry = ModelRegistry.ALL[index]
            assertEquals("the id at position $index", entry.id, info.id)
            assertEquals("the size at position $index", entry.sizeMb, info.sizeMb)
            assertEquals("the url at position $index", entry.url, info.url)
            assertEquals("the sha256 at position $index", entry.sha256, info.sha256)
        }
    }

    /** One real entry, pinned field by field against the registry's own constant. */
    @Test
    fun `byId returns one real entry with every field from the registry's own constant`() {
        val entry = ModelRegistry.SMALL

        val info = ModelCatalog.byId(entry.id)

        assertEquals("the entry the registry names must be found", entry.id, info?.id)
        assertEquals("the size of the entry", entry.sizeMb, info?.sizeMb)
        assertEquals("the url of the entry", entry.url, info?.url)
        assertEquals("the sha256 of the entry", entry.sha256, info?.sha256)
    }

    /** An id the registry does not carry has no entry. */
    @Test
    fun `byId returns null for an id the registry does not carry`() {
        assertNull("an id the registry does not carry must not come back", ModelCatalog.byId("giant"))
    }

    /** A lookup folds no case: `Small` is not `small`, whatever it looks like. */
    @Test
    fun `byId is exact-case`() {
        assertNull("a case-folded lookup must not find the lowercase id", ModelCatalog.byId("Small"))
    }
}
