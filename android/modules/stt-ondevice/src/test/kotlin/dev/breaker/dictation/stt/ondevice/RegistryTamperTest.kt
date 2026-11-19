package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Per-entry tamper checks against the real registry entries.
 *
 * For each entry, the stand-in file is written under a JUnit4
 * TemporaryFolder, is a real file, is not empty, and the pin used is
 * 64 hex, so verify steps 1 to 4 pass before the step each test aims
 * at. State after each call is drawn from ModelIntegrity.verify: exists,
 * is file, not empty, pin shape, size, digest read, digest compare, then
 * accept. The stand-ins are small, so the size handed to verify is the
 * stand-in's own length except where a test aims at the size check.
 */
class RegistryTamperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun standIn(entry: ModelEntry, bytes: ByteArray): File {
        val file = File(tmp.root, entry.id + "-standin.bin")
        return Fixtures.writeBytes(file, bytes)
    }

    private fun refused(entry: ModelEntry, result: ModelIntegrity.Verdict): ModelIntegrity.Verdict.Refused {
        assertTrue(
            "entry '${entry.id}': expected a refusal but the archive was accepted",
            result is ModelIntegrity.Verdict.Refused,
        )
        return result as ModelIntegrity.Verdict.Refused
    }

    @Test
    fun `the registry lists the five entries so the per entry checks prove something`() {
        assertTrue(
            "the registry lists no entries, so the per entry checks would prove nothing",
            ModelRegistry.ALL.isNotEmpty(),
        )
        val ids = ModelRegistry.ALL.map { it.id }
        for (wanted in listOf("small", "tiny", "base", "medium", "parakeet")) {
            assertTrue("entry '$wanted' is missing from ${ids}", ids.contains(wanted))
        }
        assertEquals(
            "the registry must list exactly the five entries, was $ids",
            setOf("small", "tiny", "base", "medium", "parakeet"),
            ids.toSet(),
        )
    }

    @Test
    fun `every registry entry refuses an archive that does not hash to its pin`() {
        for (entry in ModelRegistry.ALL) {
            val bytes = "tampered bytes for ${entry.id}".toByteArray()
            val file = standIn(entry, bytes)
            val digest = Fixtures.independentSha256(bytes)
            assertTrue(
                "entry '${entry.id}': stand-in digest must differ from the pin",
                digest != entry.sha256,
            )
            val result = ModelIntegrity.verify(file, entry.sha256, bytes.size.toLong())
            val refusal = refused(entry, result)
            assertEquals(
                "entry '${entry.id}': expected PIN_MISMATCH",
                ModelIntegrity.Refusal.PIN_MISMATCH,
                refusal.refusal,
            )
        }
    }

    @Test
    fun `every registry entry pins a positive byte size and refuses an archive of another length`() {
        for (entry in ModelRegistry.ALL) {
            assertTrue("entry '${entry.id}': the pinned byte size must be positive", entry.sizeBytes > 0L)
            val bytes = "stand-in bytes for ${entry.id}".toByteArray()
            assertTrue(
                "entry '${entry.id}': the stand-in must be shorter than the pinned size",
                bytes.size.toLong() != entry.sizeBytes,
            )
            val result = ModelIntegrity.verify(standIn(entry, bytes), entry.sha256, entry.sizeBytes)
            val refusal = refused(entry, result)
            assertEquals(
                "entry '${entry.id}': expected SIZE_MISMATCH",
                ModelIntegrity.Refusal.SIZE_MISMATCH,
                refusal.refusal,
            )
        }
    }

    @Test
    fun `a stand-in archive that hashes to its own pin and has its own size is accepted`() {
        // This accept control shows the refusals above are about the
        // digest and the size and not about a broken helper: a stand-in whose
        // digest is used as its own pin, with its own length as the pinned
        // size, passes every step and is Verified.
        for (entry in ModelRegistry.ALL) {
            val bytes = "stand-in bytes for ${entry.id}".toByteArray()
            val file = standIn(entry, bytes)
            val pin = Fixtures.independentSha256(bytes)
            val result = ModelIntegrity.verify(file, pin, bytes.size.toLong())
            assertTrue(
                "entry '${entry.id}': a stand-in hashing to its own pin must be accepted",
                result is ModelIntegrity.Verdict.Verified,
            )
            assertEquals(
                "entry '${entry.id}': the accepted digest must be the stand-in digest",
                pin,
                (result as ModelIntegrity.Verdict.Verified).digest,
            )
        }
    }
}
