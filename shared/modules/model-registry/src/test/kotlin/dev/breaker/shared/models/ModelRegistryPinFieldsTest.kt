package dev.breaker.shared.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin and presentation fields of the generated registry: `kind`,
 * `sizeBytes`, `assetId`, `label`, `rating` and `offered` on every speech
 * entry, and the five-entry speech list.
 *
 * These tests pin registry DATA only. Nothing outside the registry reads
 * `label`, `rating`, `kind` or `offered` yet: the screens that show them and
 * the recogniser `kind` selects are other modules' work and are not built.
 * The check that the generated file equals a fresh generation is a Python
 * test (tests/contract/test_model_registry_contract.py), not repeated here.
 */
class ModelRegistryPinFieldsTest {

    private fun entry(id: String): ModelEntry =
        requireNotNull(ModelRegistry.byId(id)) { "the registry has no entry '$id'" }

    @Test
    fun allIsExactlySmallTinyBaseMediumParakeetInFileOrder() {
        assertEquals(
            listOf("small", "tiny", "base", "medium", "parakeet"),
            ModelRegistry.ALL.map { it.id },
        )
    }

    @Test
    fun parakeetIsOfflineWithItsPinnedSizeAssetIdAndOwnHash() {
        val e = entry("parakeet")
        assertEquals(ModelKind.OFFLINE, e.kind)
        assertEquals(487211443L, e.sizeBytes)
        assertEquals(719769366L, e.assetId)
        assertEquals(
            "1495e91b59d7fc53ddca91f3431758ec99f8fa253fdb0e1e851be0f72b14b0f9",
            e.sha256,
        )
        assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2", e.archiveName)
        assertEquals("CC-BY-4.0", e.licence)
        assertEquals(ModelFamily.SHERPA_ONNX, e.family)
    }

    @Test
    fun theOtherSpeechEntriesHaveTheirKindAndMeasuredSizes() {
        val want = mapOf(
            "small" to Triple(ModelKind.STREAMING, 365748162L, 191972150L),
            "tiny" to Triple(ModelKind.STREAMING, 127887156L, 143510207L),
            "base" to Triple(ModelKind.OFFLINE, 208576005L, 196350763L),
            "medium" to Triple(ModelKind.OFFLINE, 1905872689L, 179372814L),
        )
        for ((id, expected) in want) {
            val e = entry(id)
            assertEquals("kind of $id", expected.first, e.kind)
            assertEquals("sizeBytes of $id", expected.second, e.sizeBytes)
            assertEquals("assetId of $id", expected.third, e.assetId)
        }
        // The whisper entries are offline models and are not offered.
        assertEquals(ModelFamily.WHISPER, entry("base").family)
        assertEquals(ModelFamily.WHISPER, entry("medium").family)
    }

    @Test
    fun offeredIsExactlySmallAndParakeet() {
        assertEquals(
            listOf("small", "parakeet"),
            ModelRegistry.ALL.filter { it.offered }.map { it.id },
        )
    }

    @Test
    fun onlyOfferedModelsCarryALabelAndARating() {
        for (e in ModelRegistry.ALL) {
            if (e.offered) {
                assertTrue("offered ${e.id} needs a label", !e.label.isNullOrBlank())
                assertTrue("offered ${e.id} needs a rating", e.rating != null)
            } else {
                assertNull("unoffered ${e.id} has a label", e.label)
                assertNull("unoffered ${e.id} has a rating", e.rating)
            }
        }
    }

    @Test
    fun smallIsGoodOneOfThreeAndParakeetIsBetterTwoOfThree() {
        assertEquals("Good", entry("small").label)
        assertEquals(1, entry("small").rating)
        assertEquals("Better", entry("parakeet").label)
        assertEquals(2, entry("parakeet").rating)
        for (e in ModelRegistry.ALL) {
            val r = e.rating ?: continue
            assertTrue("rating of ${e.id} is outside 0..3", r in 0..3)
        }
    }

    @Test
    fun everySpeechAndKeywordEntryHasAPositiveSizeBytesAndAssetId() {
        val entries = ModelRegistry.ALL + ModelRegistry.KEYWORD_MODELS
        assertEquals(6, entries.size)
        for (e in entries) {
            assertTrue("sizeBytes of ${e.id} must be > 0, was ${e.sizeBytes}", e.sizeBytes > 0L)
            assertTrue("assetId of ${e.id} must be > 0, was ${e.assetId}", e.assetId > 0L)
        }
    }

    @Test
    fun eachUrlEndsWithItsAssetIdAndSizeMbIsTheMibRoundingOfSizeBytes() {
        for (e in ModelRegistry.ALL + ModelRegistry.KEYWORD_MODELS) {
            assertTrue("sizeBytes of ${e.id} must be positive", e.sizeBytes > 0L)
            assertTrue("assetId of ${e.id} must be positive", e.assetId > 0L)
            assertTrue(
                "url of ${e.id} must end with its assetId ${e.assetId}: ${e.url}",
                e.url.endsWith("/releases/assets/${e.assetId}"),
            )
            assertEquals(
                "sizeMb of ${e.id}",
                ((e.sizeBytes + 524288L) / 1048576L).toInt(),
                e.sizeMb,
            )
        }
    }

    @Test
    fun theKeywordEntryCarriesItsPinAndNoSpeechPresentation() {
        val e = ModelRegistry.KWS_GIGASPEECH_3M
        assertEquals(17626723L, e.sizeBytes)
        assertEquals(166405338L, e.assetId)
        assertFalse(e.offered)
        assertNull(e.label)
        assertNull(e.rating)
    }

    @Test
    fun anOldPositionalConstructionStillCompilesAndGetsTheDefaults() {
        val e = ModelEntry("x", ModelFamily.WHISPER, "u", "s", 1, "l", false, "a.tar.bz2")
        assertEquals(ModelKind.STREAMING, e.kind)
        assertEquals(0L, e.sizeBytes)
        assertEquals(0L, e.assetId)
        assertNull(e.label)
        assertNull(e.rating)
        assertFalse(e.offered)
    }
}
