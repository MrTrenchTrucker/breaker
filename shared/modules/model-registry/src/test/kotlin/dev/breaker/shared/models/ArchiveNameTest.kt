package dev.breaker.shared.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upstream file name each registry entry carries (archiveName): its
 * committed values, its match with models.yaml, its plain-file-name shape,
 * and the seven-argument construction that the property's LAST position and
 * its default exist to keep compiling.
 *
 * models.yaml is read here only as plain lines (an `archive_name:` scan),
 * the same convention ModelRegistryTest uses for the ids  -  no YAML parser,
 * the no-runtime-YAML rule (ADR-016) is about the phone, and the Python
 * contract test owns the yaml<->kotlin drift check.
 */
class ArchiveNameTest {

    private fun modelsYaml(): List<String> {
        val root = System.getProperty("breaker.moduleRoot")
        assertNotNull("the build must set the breaker.moduleRoot test property", root)
        return File(root, "models.yaml").readLines()
    }

    private fun yamlArchiveNames(lines: List<String> = modelsYaml()): Map<String, String> {
        // Anchor on the indent of the first `archive_name:` field line. A
        // notes body (`notes: >-`) is prose indented deeper than the field
        // lines, so a wrapped note line cannot pass for a field.
        val fieldIndent = lines.firstOrNull { it.startsWith("    archive_name: ") }
            ?.takeWhile { it == ' ' }?.length
            ?: return emptyMap()
        val field = Regex("""^ {${fieldIndent}}archive_name:\s*(\S+)\s*$""")
        val item = Regex("""^\s*-\s+id:\s*(\S+)\s*$""")
        val byId = LinkedHashMap<String, String>()
        var id: String? = null
        for (line in lines) {
            val m = item.find(line)
            if (m != null) {
                id = m.groupValues[1]
                continue
            }
            val f = field.find(line)
            if (f != null && id != null) {
                byId[id] = f.groupValues[1]
            }
        }
        return byId
    }

    @Test
    fun theCommittedNamesAreTheUpstreamFileNames() {
        assertEquals(
            "sherpa-onnx-streaming-zipformer-en-2023-06-21-mobile.tar.bz2",
            ModelRegistry.SMALL.archiveName,
        )
        assertEquals(
            "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
            ModelRegistry.TINY.archiveName,
        )
        assertEquals("sherpa-onnx-whisper-base.en.tar.bz2", ModelRegistry.BASE.archiveName)
        assertEquals(
            "sherpa-onnx-whisper-medium.en.tar.bz2",
            ModelRegistry.MEDIUM.archiveName,
        )
    }

    @Test
    fun everyCommittedNameIsTheLineModelsYamlCarries() {
        val yaml = yamlArchiveNames()
        for (e in ModelRegistry.ALL) {
            assertTrue("models.yaml carries no archive_name line for '${e.id}'",
                yaml.containsKey(e.id))
            assertEquals(
                "the committed name for '${e.id}' must be models.yaml's line byte for byte",
                yaml[e.id],
                e.archiveName,
            )
        }
    }

    @Test
    fun aNameIsAPlainFileName() {
        for (e in ModelRegistry.ALL) {
            assertFalse("archiveName of '${e.id}' must not carry '/'",
                e.archiveName.contains("/"))
            assertFalse("archiveName of '${e.id}' must not carry a backslash",
                e.archiveName.contains("\\"))
        }
    }

    @Test
    fun aSevenArgumentConstructionStillCompilesAndTheDefaultIsEmpty() {
        // The module's fakes build entries with seven positional arguments
        // (id, family, url, sha256, sizeMb, licence, hosted). archiveName
        // sits LAST with a default, so that construction keeps compiling
        // and reports the default.
        val e = ModelEntry(
            "tiny",
            ModelFamily.SHERPA_ONNX,
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/1",
            "0".repeat(64),
            1,
            "Apache-2.0",
            false,
        )
        assertEquals("the default of a construction without the argument is the empty string",
            "",
            e.archiveName,
        )
    }
}
