package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The per-archive rule of [ModelIntegrity.verify]: when an archive name is
 * given, the digest listed for that name must be the pin. A pin listed only on
 * another archive's line is refused. A null or blank name keeps the set match.
 */
class ModelIntegrityArchiveNameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val thisArchive = "this-model.tar.bz2"
    private val otherArchive = "other-model.tar.bz2"

    /** Writes [content] to a file and returns it with its SHA-256 digest. */
    private fun archiveFile(content: String): Pair<File, String> {
        val bytes = content.toByteArray(Charsets.US_ASCII)
        val file = Fixtures.writeBytes(File(tmp.root, "archive.bin"), bytes)
        return file to Fixtures.independentSha256(bytes)
    }

    /** Builds the checksum list from real checksum-file lines, through the production parser. */
    private fun checksumsOf(vararg lines: String): UpstreamChecksums.Checksums =
        UpstreamChecksums.parse(lines.joinToString("\n"))

    private fun refusedOf(verdict: ModelIntegrity.Verdict): ModelIntegrity.Verdict.Refused {
        assertTrue("expected a refusal but got $verdict", verdict is ModelIntegrity.Verdict.Refused)
        return verdict as ModelIntegrity.Verdict.Refused
    }

    @Test
    fun `pin listed only on another archive line is refused when a name is given`() {
        val (file, digest) = archiveFile("archive bytes")
        val checksums = checksumsOf("$otherArchive\t$digest", "$thisArchive\t${Fixtures.OTHER_PIN}")
        val result = refusedOf(ModelIntegrity.verify(file, digest, checksums, thisArchive))
        assertEquals(
            "a pin listed only on another archive's line must be refused as PIN_NOT_IN_UPSTREAM",
            ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
            result.refusal,
        )
        assertTrue(
            "the refusal detail must name the archive: ${result.detail}",
            result.detail.contains(thisArchive),
        )
    }

    @Test
    fun `pin on the named archive own line is verified`() {
        val (file, digest) = archiveFile("archive bytes")
        val checksums = checksumsOf("$otherArchive\t${Fixtures.OTHER_PIN}", "$thisArchive\t$digest")
        val verdict = ModelIntegrity.verify(file, digest, checksums, thisArchive)
        assertTrue(
            "the pin on the named archive's own line must verify: $verdict",
            verdict is ModelIntegrity.Verdict.Verified,
        )
        assertEquals(digest, (verdict as ModelIntegrity.Verdict.Verified).digest)
    }

    @Test
    fun `named archive missing from the checksum file is refused when the pin sits on another line`() {
        val (file, digest) = archiveFile("archive bytes")
        val checksums = checksumsOf("$otherArchive\t$digest", "unrelated-model.tar.bz2\t${Fixtures.OTHER_PIN}")
        val result = refusedOf(ModelIntegrity.verify(file, digest, checksums, thisArchive))
        assertEquals(
            "an archive missing from the checksum file must be refused as PIN_NOT_IN_UPSTREAM",
            ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
            result.refusal,
        )
        assertTrue(
            "the refusal detail must name the archive: ${result.detail}",
            result.detail.contains(thisArchive),
        )
    }

    @Test
    fun `named archive listed with another digest is refused when the pin sits on a later other line`() {
        val (file, digest) = archiveFile("archive bytes")
        val checksums = checksumsOf(
            "first-model.tar.bz2\t${Fixtures.OTHER_PIN}",
            "$thisArchive\t${Fixtures.OTHER_PIN.replace('b', 'c')}",
            "$otherArchive\t$digest",
        )
        val result = refusedOf(ModelIntegrity.verify(file, digest, checksums, thisArchive))
        assertEquals(
            "an archive listed with a different digest must be refused as PIN_NOT_IN_UPSTREAM",
            ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
            result.refusal,
        )
        assertTrue(
            "the refusal detail must name the archive: ${result.detail}",
            result.detail.contains(thisArchive),
        )
    }

    @Test
    fun `upper case pin matches a lower case listed digest when a name is given`() {
        val (file, digest) = archiveFile("archive bytes")
        val checksums = checksumsOf("$thisArchive\t$digest")
        val verdict = ModelIntegrity.verify(file, digest.uppercase(), checksums, thisArchive)
        assertTrue(
            "an upper case pin equal to the listed digest must verify: $verdict",
            verdict is ModelIntegrity.Verdict.Verified,
        )
        assertEquals(digest, (verdict as ModelIntegrity.Verdict.Verified).digest)
    }

    @Test
    fun `null and blank archive names keep the set match and verify a pin on another line`() {
        val (file, digest) = archiveFile("archive bytes")
        val checksums = checksumsOf("$otherArchive\t$digest", "$thisArchive\t${Fixtures.OTHER_PIN}")
        for (name in listOf<String?>(null, "", "   ")) {
            val verdict = ModelIntegrity.verify(file, digest, checksums, name)
            assertTrue(
                "archive name [$name] (null means no name) must keep the set match and verify: $verdict",
                verdict is ModelIntegrity.Verdict.Verified,
            )
        }
    }

    @Test
    fun `pin listed nowhere gives the same refusal text as before when a name is given`() {
        val (file, digest) = archiveFile("archive bytes")
        val checksums = checksumsOf("$thisArchive\t${Fixtures.OTHER_PIN}")
        val result = refusedOf(ModelIntegrity.verify(file, digest, checksums, thisArchive))
        assertEquals(
            "a pin listed nowhere must still be refused as PIN_NOT_IN_UPSTREAM",
            ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM,
            result.refusal,
        )
        assertEquals(
            "the existing refusal text for an unlisted pin must not change",
            "pin $digest not found in upstream checksums",
            result.detail,
        )
    }
}
