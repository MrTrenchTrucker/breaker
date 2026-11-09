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
 * at. State after each call is drawn from ModelIntegrity.verify steps
 * 1 to 8: exists, is file, not empty, pin shape, upstream set check
 * plus per-archive check, digest read, digest compare, then accept.
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
    fun `the registry lists the four entries so the per entry checks prove something`() {
        assertTrue(
            "the registry lists no entries, so the per entry checks would prove nothing",
            ModelRegistry.ALL.isNotEmpty(),
        )
        val ids = ModelRegistry.ALL.map { it.id }
        for (wanted in listOf("small", "tiny", "base", "medium")) {
            assertTrue("entry '$wanted' is missing from ${ids}", ids.contains(wanted))
        }
        assertEquals(
            "the registry must list exactly the four entries, was $ids",
            setOf("small", "tiny", "base", "medium"),
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
            val checksums = Fixtures.checksumsOf(entry.archiveName to entry.sha256)
            val result = ModelIntegrity.verify(file, entry.sha256, checksums, entry.archiveName)
            val refusal = refused(entry, result)
            assertEquals(
                "entry '${entry.id}': expected PIN_MISMATCH",
                ModelIntegrity.Refusal.PIN_MISMATCH,
                refusal.refusal,
            )
        }
    }

    @Test
    fun `every registry entry refuses a pin the upstream list does not hold`() {
        for (entry in ModelRegistry.ALL) {
            val bytes = "stand-in bytes for ${entry.id}".toByteArray()
            val file = standIn(entry, bytes)
            val other = Fixtures.OTHER_PIN
            assertTrue(
                "entry '${entry.id}': the other digest must differ from the pin",
                other != entry.sha256,
            )
            val checksums = Fixtures.checksumsOf(entry.archiveName to other)
            val result = ModelIntegrity.verify(file, entry.sha256, checksums, entry.archiveName)
            val refusal = refused(entry, result)
            assertEquals(
                "entry '${entry.id}': expected PIN_NOT_IN_UPSTREAM",
                ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
                refusal.refusal,
            )
        }
    }

    @Test
    fun `every registry entry refuses its pin listed under a different archive name`() {
        for (entry in ModelRegistry.ALL) {
            val bytes = "stand-in bytes for ${entry.id}".toByteArray()
            val file = standIn(entry, bytes)
            val otherName = "other-${entry.id}.bin"
            val checksums = Fixtures.checksumsOf(otherName to entry.sha256)
            val result = ModelIntegrity.verify(file, entry.sha256, checksums, entry.archiveName)
            val refusal = refused(entry, result)
            assertEquals(
                "entry '${entry.id}': expected PIN_NOT_IN_UPSTREAM from the per-archive check",
                ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
                refusal.refusal,
            )
        }
    }

    @Test
    fun `every registry entry refuses an unlisted pin when no archive name is given`() {
        for (entry in ModelRegistry.ALL) {
            val file = standIn(entry, "stand-in bytes for ${entry.id}".toByteArray())
            val checksums = Fixtures.checksumsOf("some-other-archive.bin" to Fixtures.OTHER_PIN)
            val refusal = refused(entry, ModelIntegrity.verify(file, entry.sha256, checksums))
            assertEquals(
                "entry '${entry.id}': expected PIN_NOT_IN_UPSTREAM from the set check alone",
                ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
                refusal.refusal,
            )
        }
    }

    @Test
    fun `every registry entry refuses its archive name listed with another digest`() {
        for (entry in ModelRegistry.ALL) {
            val file = standIn(entry, "stand-in bytes for ${entry.id}".toByteArray())
            val checksums = Fixtures.checksumsOf(
                entry.archiveName to Fixtures.OTHER_PIN,
                "other-${entry.id}.bin" to entry.sha256,
            )
            val result = ModelIntegrity.verify(file, entry.sha256, checksums, entry.archiveName)
            val refusal = refused(entry, result)
            assertEquals(
                "entry '${entry.id}': expected PIN_NOT_IN_UPSTREAM from the per-archive digest",
                ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
                refusal.refusal,
            )
        }
    }

    @Test
    fun `a stand-in archive that hashes to its own pin is accepted`() {
        // This accept control shows the refusals above are about the
        // digest and not about a broken helper: a stand-in whose digest
        // is used as its own pin and listed under its own archive name
        // passes verify steps 1 to 8 and is Verified.
        for (entry in ModelRegistry.ALL) {
            val bytes = "stand-in bytes for ${entry.id}".toByteArray()
            val file = standIn(entry, bytes)
            val pin = Fixtures.independentSha256(bytes)
            val checksums = Fixtures.checksumsOf(entry.archiveName to pin)
            val result = ModelIntegrity.verify(file, pin, checksums, entry.archiveName)
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
