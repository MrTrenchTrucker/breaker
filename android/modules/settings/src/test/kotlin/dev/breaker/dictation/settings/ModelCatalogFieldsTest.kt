package dev.breaker.dictation.settings

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label, rating and offer the catalog shows, pinned against the registry's own entries.
 *
 * The words Good and Better and the ratings one and two are written here as
 * literals on purpose, so a projection that swaps or drops them costs the test.
 * Every other expected value is read from the registry itself.
 */
class ModelCatalogFieldsTest {

    /** Only the small model and the parakeet model are offered, in the registry's order. */
    @Test
    fun `offered list is exactly small and parakeet`() {
        assertEquals(listOf("small", "parakeet"), ModelCatalog.offered().map { it.id })
    }

    /** The existing model is the good one, rated one truck of three. */
    @Test
    fun `small is labelled Good and rated one of three`() {
        val info = ModelCatalog.byId("small")
        assertEquals("Good", info?.label)
        assertEquals(1, info?.rating)
        assertTrue("small must be offered", info?.offered == true)
    }

    /** The Parakeet model is the better one, rated two trucks of three. */
    @Test
    fun `parakeet is labelled Better and rated two of three`() {
        val info = ModelCatalog.byId("parakeet")
        assertEquals("Better", info?.label)
        assertEquals(2, info?.rating)
        assertTrue("parakeet must be offered", info?.offered == true)
    }

    /** A model the registry does not offer carries no label, no rating and no offer. */
    @Test
    fun `unoffered models carry no label no rating and no offer`() {
        for (id in listOf("tiny", "base", "medium")) {
            val info = ModelCatalog.byId(id)
            assertNull("the label of $id", info?.label)
            assertNull("the rating of $id", info?.rating)
            assertFalse("the offer of $id", info?.offered == true)
        }
    }

    /** Every rating the catalog shows is a count of trucks from zero to three. */
    @Test
    fun `every rating the catalog shows is between zero and three`() {
        for (info in ModelCatalog.all()) {
            val rating = info.rating ?: continue
            assertTrue("the rating of ${info.id} is $rating, outside zero to three", rating in 0..3)
        }
    }

    /** The label, rating and offer of each catalog entry are the registry's own, field for field. */
    @Test
    fun `every catalog entry carries the label rating and offer of its registry entry`() {
        val listed = ModelCatalog.all()
        assertEquals("one entry per registry model", ModelRegistry.ALL.size, listed.size)
        ModelRegistry.ALL.forEachIndexed { index, entry ->
            assertEquals("the label at position $index", entry.label, listed[index].label)
            assertEquals("the rating at position $index", entry.rating, listed[index].rating)
            assertEquals("the offer at position $index", entry.offered, listed[index].offered)
        }
    }

    /** The offered list is exactly the catalog entries whose offer flag is set, in the same order. */
    @Test
    fun `offered is exactly the catalog entries whose offer flag is set`() {
        val flagged = ModelCatalog.all().filter { it.offered }
        assertEquals(flagged, ModelCatalog.offered())
    }
}
