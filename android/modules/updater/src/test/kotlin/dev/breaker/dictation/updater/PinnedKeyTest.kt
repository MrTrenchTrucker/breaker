package dev.breaker.dictation.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the key id syntax and the rules for a pinned list. */
internal class PinnedKeyTest {
    @Test
    fun wellFormedKeyIdsAreAccepted() {
        assertTrue(isWellFormedKeyId("a"))
        assertTrue(isWellFormedKeyId("a".repeat(64)))
        assertTrue(isWellFormedKeyId("A.b_c-9"))
    }

    @Test
    fun malformedKeyIdsAreRefused() {
        assertFalse(isWellFormedKeyId(""))
        assertFalse(isWellFormedKeyId("a".repeat(65)))
        assertFalse(isWellFormedKeyId("a b"))
        assertFalse(isWellFormedKeyId("a/b"))
        assertFalse(isWellFormedKeyId("a\u00e9"))
    }

    @Test
    fun duplicateIdListIsNotUsable() {
        val keys = listOf(
            PinnedKey(ManifestVectors.CURRENT_KEY_ID, ManifestVectors.CURRENT_SPKI),
            PinnedKey(ManifestVectors.CURRENT_KEY_ID, ManifestVectors.NEXT_SPKI),
        )
        assertFalse(pinnedKeysAreUsable(keys))
    }

    @Test
    fun emptyListIsNotUsable() {
        assertFalse(pinnedKeysAreUsable(emptyList()))
    }

    @Test
    fun pinnedKeyDoesNotPrintKeyText() {
        val text = PinnedKey(ManifestVectors.CURRENT_KEY_ID, ManifestVectors.CURRENT_SPKI).toString()
        assertFalse(text.contains(ManifestVectors.CURRENT_SPKI))
        assertTrue(text.contains(ManifestVectors.CURRENT_KEY_ID))
    }
}
