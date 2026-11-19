package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The old per-archive-name rule (a digest listed for the archive's name in upstream's list
 * had to be the pin) no longer exists: [ModelIntegrity.verify] takes no name and no list.
 * These tests pin what replaced it: the file name plays no part, only the pinned size and sha256 decide.
 */
class ModelIntegrityArchiveNameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the same bytes verify under any file name`() {
        val bytes = "archive bytes".toByteArray(Charsets.US_ASCII)
        val digest = Fixtures.independentSha256(bytes)
        for (name in listOf("this-model.tar.bz2", "other-model.tar.bz2", "x")) {
            val file = Fixtures.writeBytes(File(tmp.root, name), bytes)
            val verdict = ModelIntegrity.verify(file, digest, bytes.size.toLong())
            assertTrue("file name [$name] must not matter: $verdict", verdict is ModelIntegrity.Verdict.Verified)
        }
    }

    @Test
    fun `a pin that belongs to other bytes is refused whatever the file is called`() {
        val bytes = "archive bytes".toByteArray(Charsets.US_ASCII)
        val file = Fixtures.writeBytes(File(tmp.root, "this-model.tar.bz2"), bytes)
        val verdict = ModelIntegrity.verify(file, Fixtures.OTHER_PIN, bytes.size.toLong())
        assertEquals(
            ModelIntegrity.Refusal.PIN_MISMATCH,
            (verdict as ModelIntegrity.Verdict.Refused).refusal,
        )
    }
}
