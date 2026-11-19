package dev.breaker.shared.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine fields of the generated registry (ADR-050 J: models are data):
 * `modelType`, `files`, `limits`, `language`, `isDefault` and `notice` on every
 * speech entry, `ModelRegistry.SUPPORTED_TYPES` and `ModelEntry.isSupported`.
 *
 * These tests pin registry DATA and the generated check only. They are written
 * and traced by hand and are not compiled or run in this tree; the Python tests
 * of the generator (tests/unit/shared/model-registry) are the ones that run
 * here. No app module reads these fields yet in this file's scope.
 */
class ModelRegistryEngineFieldsTest {

    private val mib = 1_048_576L

    private fun entry(id: String): ModelEntry =
        requireNotNull(ModelRegistry.byId(id)) { "the registry has no entry '$id'" }

    @Test
    fun theSupportedSetIsStreamingWithNoTypeAndOfflineNemoTransducer() {
        assertEquals(
            setOf(ModelKind.STREAMING to "", ModelKind.OFFLINE to "nemo_transducer"),
            ModelRegistry.SUPPORTED_TYPES,
        )
    }

    @Test
    fun smallCarriesItsStreamingFilesLimitsAndLanguage() {
        val e = entry("small")
        assertEquals("", e.modelType)
        assertEquals(
            ModelFiles(
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
            ),
            e.files,
        )
        assertEquals(ModelLimits(300 * mib, 160 * mib, 448 * mib, 32, 4), e.limits)
        assertEquals("en", e.language)
        assertNull(e.notice)
    }

    @Test
    fun tinyCarriesItsOwnJoinerAndLimits() {
        val e = entry("tiny")
        assertEquals("joiner-epoch-99-avg-1.onnx", e.files?.joiner)
        assertEquals(ModelLimits(128 * mib, 80 * mib, 160 * mib, 32, 4), e.limits)
        assertEquals("en", e.language)
    }

    @Test
    fun parakeetIsOfflineNemoTransducerWithTheMeasuredLimitsAndNoLanguage() {
        val e = entry("parakeet")
        assertEquals("nemo_transducer", e.modelType)
        assertEquals(
            ModelFiles("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            e.files,
        )
        assertEquals(ModelLimits(640 * mib, 704 * mib, 720 * mib, 32, 4), e.limits)
        assertEquals("", e.language)
    }

    @Test
    fun onlyParakeetCarriesTheNoticeAndItIsTheOwnersText() {
        assertEquals(
            "This app includes the Parakeet TDT 0.6B v3 model by NVIDIA Corporation " +
                "(https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), licensed under CC BY 4.0 " +
                "(https://creativecommons.org/licenses/by/4.0/). " +
                "Model weights were converted/quantized from the original.",
            entry("parakeet").notice,
        )
        assertEquals(listOf("parakeet"), ModelRegistry.ALL.filter { it.notice != null }.map { it.id })
    }

    @Test
    fun exactlyOneEntryIsTheDefaultAndItIsOfferedAndSmall() {
        val defaults = ModelRegistry.ALL.filter { it.isDefault }
        assertEquals(listOf("small"), defaults.map { it.id })
        assertTrue(defaults.single().offered)
    }

    @Test
    fun theWhisperEntriesAreUnsupportedAndNotOfferedAndHaveNoEnginePath() {
        for (id in listOf("base", "medium")) {
            val e = entry(id)
            assertEquals("whisper", e.modelType)
            assertFalse(id, e.isSupported)
            assertFalse(id, e.offered)
            assertNull(id, e.files)
            assertNull(id, e.limits)
            assertFalse(id, e.isDefault)
        }
    }

    @Test
    fun everySupportedEntryIsCompleteAndEveryOfferedEntryIsSupported() {
        for (e in ModelRegistry.ALL) {
            if (e.isSupported) {
                assertNotNull(e.id, e.files)
                assertNotNull(e.id, e.limits)
                assertNotNull(e.id, e.language)
                assertTrue(e.id, (e.kind to e.modelType) in ModelRegistry.SUPPORTED_TYPES)
            }
            if (e.offered) assertTrue(e.id, e.isSupported)
        }
        assertEquals(listOf("small", "tiny", "parakeet"), ModelRegistry.ALL.filter { it.isSupported }.map { it.id })
    }

    @Test
    fun theSupportedCheckNeedsEveryPartAndThePairNotTheTypeAlone() {
        val parakeet = entry("parakeet")
        assertTrue(parakeet.isSupported)
        assertFalse(parakeet.copy(files = null).isSupported)
        assertFalse(parakeet.copy(limits = null).isSupported)
        assertFalse(parakeet.copy(language = null).isSupported)
        assertFalse(parakeet.copy(modelType = null).isSupported)
        // a supported type under the wrong kind is not a supported pair
        assertFalse(parakeet.copy(kind = ModelKind.STREAMING).isSupported)
        assertFalse(entry("small").copy(modelType = "nemo_transducer").isSupported)
    }

    @Test
    fun aKeywordEntryIsNotASpeechModelAndCarriesNoEngineField() {
        val kw = ModelRegistry.KEYWORD_MODELS.single()
        assertNull(kw.modelType)
        assertNull(kw.files)
        assertNull(kw.limits)
        assertNull(kw.language)
        assertNull(kw.notice)
        assertFalse(kw.isDefault)
        assertFalse(kw.isSupported)
    }
}
