package dev.breaker.shared.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyword model list of the generated registry: `KEYWORD_MODELS`,
 * `KWS_GIGASPEECH_3M` and `byKeywordId`, beside the speech list `ALL` and
 * `byId`, which stay speech only. The app does not download or load the
 * keyword model yet: these tests pin the registry data only.
 *
 * models.yaml is read here only as plain lines (an `id:` scan under one
 * top-level key), never by a YAML parser, as in ModelRegistryTest.
 */
class KeywordRegistryTest {

    private val speechIds = listOf("small", "tiny", "base", "medium", "parakeet")
    private val keywordId = "kws-gigaspeech-3m"

    /** The ids of the sequence items under the top-level key [key], in file order. */
    private fun idsUnder(key: String): List<String> {
        val root = System.getProperty("breaker.moduleRoot")
        assertNotNull("the build must set the breaker.moduleRoot test property", root)
        val lines = File(root, "models.yaml").readLines()
        val at = lines.indexOfFirst { it == "$key:" }
        assertTrue("models.yaml has no top-level $key key", at >= 0)
        val body = lines.drop(at + 1).takeWhile { !Regex("""^[A-Za-z_][A-Za-z0-9_]*:""").containsMatchIn(it) }
        return body.mapNotNull { Regex("""^ {2}-\s+id:\s*(\S+)\s*$""").find(it)?.groupValues?.get(1) }
    }

    @Test
    fun theSpeechListIsExactlySmallTinyBaseMediumParakeet() {
        assertEquals(
            "model-registry: the speech list must stay exactly the five speech ids, in order",
            speechIds,
            ModelRegistry.ALL.map { it.id },
        )
    }

    @Test
    fun theKeywordListIsExactlyTheOnePinnedEntry() {
        assertEquals(1, ModelRegistry.KEYWORD_MODELS.size)
        val e = ModelRegistry.KEYWORD_MODELS[0]
        assertSame("KWS_GIGASPEECH_3M must be the list's one entry", ModelRegistry.KWS_GIGASPEECH_3M, e)
        assertEquals(keywordId, e.id)
        assertEquals(ModelFamily.SHERPA_ONNX, e.family)
        assertEquals(
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/166405338",
            e.url,
        )
        assertEquals(
            "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a",
            e.sha256,
        )
        assertEquals(17, e.sizeMb)
        assertEquals("Apache-2.0", e.licence)
        assertEquals(false, e.hosted)
        assertEquals(
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2",
            e.archiveName,
        )
    }

    @Test
    fun byKeywordIdAnswersTheKeywordIdAndNullForASpeechId() {
        assertSame(ModelRegistry.KWS_GIGASPEECH_3M, ModelRegistry.byKeywordId(keywordId))
        for (id in speechIds) {
            assertNull("byKeywordId must not answer the speech id $id", ModelRegistry.byKeywordId(id))
        }
        assertNull(ModelRegistry.byKeywordId("nope"))
        assertNull(ModelRegistry.byKeywordId("KWS-GIGASPEECH-3M"))
    }

    @Test
    fun byIdAnswersNullForTheKeywordId() {
        assertNull(
            "byId is speech only: the keyword id must not be found",
            ModelRegistry.byId(keywordId),
        )
        for (id in speechIds) {
            assertNotNull("byId must still answer $id", ModelRegistry.byId(id))
        }
    }

    @Test
    fun theTwoListsShareNoId() {
        val speech = ModelRegistry.ALL.map { it.id }.toSet()
        val keyword = ModelRegistry.KEYWORD_MODELS.map { it.id }.toSet()
        assertTrue("the lists must not share an id", speech.intersect(keyword).isEmpty())
        assertFalse("ALL must not hold a keyword entry", ModelRegistry.ALL.contains(ModelRegistry.KWS_GIGASPEECH_3M))
    }

    @Test
    fun theKeywordListIsEveryKeywordEntryOfModelsYamlInOrder() {
        assertEquals(
            "KEYWORD_MODELS must list exactly the keyword_models entries, in file order",
            idsUnder("keyword_models"),
            ModelRegistry.KEYWORD_MODELS.map { it.id },
        )
        assertEquals(
            "ALL must list exactly the models entries, not the keyword ones",
            idsUnder("models"),
            ModelRegistry.ALL.map { it.id },
        )
    }

    @Test
    fun theKeywordEntryHas64LowercaseHexAndAPositiveSize() {
        for (e in ModelRegistry.KEYWORD_MODELS) {
            assertTrue("sha256 of ${e.id} must be 64 lowercase hex, was ${e.sha256}",
                e.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("sizeMb of ${e.id} must be positive", e.sizeMb > 0)
        }
    }
}
