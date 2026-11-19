package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The loader keeps two lookups: the one that finds the entry to load and the one that finds the
 * entry an unpack profile comes from. Their defaults must be the same function, the real registry,
 * so a production loader never reads two different answers for one id. The defaults are private,
 * so this test reads them from the built loader's fields. Every mutant named here is hand traced, not run.
 */
class ModelLoaderLookupDefaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Suppress("UNCHECKED_CAST")
    private fun field(loader: ModelLoader, name: String): (String) -> ModelEntry? {
        val f = ModelLoader::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(loader) as (String) -> ModelEntry?
    }

    /** Every registry id, plus ids the registry does not know (empty, wrong case, unlisted). */
    private fun probeIds(): List<String> = ModelRegistry.ALL.map { it.id } + listOf("", "Small", "no-such-model")

    /** The ids on which two lookups do not give the same entry. */
    private fun disagreements(a: (String) -> ModelEntry?, b: (String) -> ModelEntry?): List<String> =
        probeIds().filter { a(it) !== b(it) }

    @Test
    fun `the entry lookup and the profile lookup of a default loader are the registry and agree on every id`() {
        val loader = ModelLoader(LocalModelStore(tmp.newFolder()))
        val entryLookup = field(loader, "lookup")
        val profileLookup = field(loader, "profileLookup")
        // Killer of a default that drops an id (null for one id): that id is listed here and the list is not empty.
        assertEquals("ids on which the two defaults differ", emptyList<String>(), disagreements(entryLookup, profileLookup))
        // Killer of both defaults replaced by the same wrong function: each is also the registry's own answer.
        for (id in probeIds()) {
            assertSame("entry lookup for '$id'", ModelRegistry.byId(id), entryLookup(id))
            assertSame("profile lookup for '$id'", ModelRegistry.byId(id), profileLookup(id))
        }
    }

    @Test
    fun `a loader given only an entry lookup keeps the registry as its profile lookup`() {
        val loader = ModelLoader(LocalModelStore(tmp.newFolder()), lookup = { null })
        assertNull("the injected entry lookup knows nothing", field(loader, "lookup")("small"))
        // Killer of a profile default that follows the injected lookup (profileLookup = lookup): it would answer null here.
        val profileLookup = field(loader, "profileLookup")
        for (id in probeIds()) assertSame("profile lookup for '$id'", ModelRegistry.byId(id), profileLookup(id))
    }

    @Test
    fun `the comparison can fail a lookup that drops one id is reported`() {
        val dropped = ModelRegistry.ALL.first().id
        val dropping = { id: String -> if (id == dropped) null else ModelRegistry.byId(id) }
        // Control for the first test: the instrument names the one id on which the two lookups differ.
        assertEquals(listOf(dropped), disagreements({ id -> ModelRegistry.byId(id) }, dropping))
        assertEquals(emptyList<String>(), disagreements({ id -> ModelRegistry.byId(id) }, { id -> ModelRegistry.byId(id) }))
    }
}
