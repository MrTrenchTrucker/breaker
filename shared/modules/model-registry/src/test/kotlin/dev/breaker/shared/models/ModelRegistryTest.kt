package dev.breaker.shared.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the generated model registry (ADR-016): the app names
 * models through these constants, so the constants are the contract.
 *
 * The models.yaml file is read here only as plain lines (an `id:` scan),
 * never by a YAML parser — the no-runtime-YAML rule (ADR-016) is about the
 * phone, and the Python contract test owns the yaml<->kotlin drift check.
 */
class ModelRegistryTest {

    private fun modelsYaml(): List<String> {
        // The build passes the module root as a test property (build.gradle.kts)
        // so the test never has to guess where it is running from.
        val root = System.getProperty("breaker.moduleRoot")
        assertNotNull("the build must set the breaker.moduleRoot test property", root)
        return File(root, "models.yaml").readLines()
    }

    /**
     * The ids of the sequence items under the top-level `models:` key, in file
     * order. Anchored to the indent of the first item: block-scalar content
     * (a `notes: >-` body) is prose and is indented deeper, so a notes line
     * that happens to read like `- id: ...` cannot pass for an entry.
     */
    private fun yamlIds(lines: List<String> = modelsYaml()): List<String> {
        val modelsAt = lines.indexOfFirst { it == "models:" }
        require(modelsAt >= 0) { "models.yaml has no top-level `models:` key" }
        val itemIndent = lines.drop(modelsAt + 1)
            .firstOrNull { ITEM_START.containsMatchIn(it) }
            ?.takeWhile { it == ' ' }?.length
            ?: return emptyList()
        val item = Regex("""^ {${itemIndent}}-\s+id:\s*(\S+)\s*$""")
        return lines.drop(modelsAt + 1).mapNotNull { item.find(it)?.groupValues?.get(1) }
    }

    private val ITEM_START = Regex("""^\s+-\s+""")

    @Test
    fun byIdReturnsTheKnownModelWithItsPinnedFields() {
        val entry = ModelRegistry.byId("small")
        assertNotNull("byId must find the 'small' entry", entry)
        val e = entry!!
        assertEquals("small", e.id)
        assertEquals(ModelFamily.SHERPA_ONNX, e.family)
        assertEquals(
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/191972150",
            e.url,
        )
        assertEquals(
            "b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422",
            e.sha256,
        )
        assertEquals(349, e.sizeMb)
        assertEquals("Apache-2.0", e.licence)
        assertEquals(false, e.hosted)
    }

    @Test
    fun byIdReturnsNullForAnUnknownId() {
        assertNull("the app refuses unknown model ids", ModelRegistry.byId("nope"))
        assertNull(ModelRegistry.byId("SMALL"))
    }

    @Test
    fun allListsEveryEntryInModelsYamlOrder() {
        assertEquals("ALL must list exactly the models.yaml entries, in file order",
            yamlIds(),
            ModelRegistry.ALL.map { it.id },
        )
    }

    @Test
    fun aNotesBlockScalarCannotBeCountedAsAnEntry() {
        // The shape mirrors models.yaml: one entry under `models:`, whose
        // `notes: >-` folded scalar carries free text that itself looks like
        // sequence items. Those lines are prose, not registry entries.
        val yaml = listOf(
            "models:",
            "  - id: small",
            "    notes: >-",
            "      - id: phantom-one",
            "      - id: phantom-two",
            "",
        )
        assertEquals(
            "only the indented item under `models:` is an entry; notes text is not",
            listOf("small"),
            yamlIds(yaml),
        )
    }

    @Test
    fun everySha256Is64LowercaseHex() {
        for (e in ModelRegistry.ALL) {
            assertTrue("sha256 of ${e.id} must be 64 lowercase hex, was ${e.sha256}",
                e.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }
}
