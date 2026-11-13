package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tamper checks against the REAL model archives. The stand-in RegistryTamperTest proves the refusal
 * logic on made-up bytes; this one proves it on the files the pins were taken from.
 *
 * The real archives are large and are not in the repository. The folder that holds them is named by
 * the environment variable [DIR_VARIABLE]; each archive is `<folder>/<archive name of its entry>`.
 * With the variable unset (the normal case) both tests are SKIPPED. With it set, a missing folder or
 * a missing archive is a FAILURE, never a skip, so a wrong path cannot pass for a green run.
 */
class RealArchiveTamperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun realFolder(): File {
        val path = System.getenv(DIR_VARIABLE)
        assumeTrue("$DIR_VARIABLE is not set: the real archives are not here", path != null && path.isNotBlank())
        val folder = File(path!!)
        assertTrue("$DIR_VARIABLE names a folder that does not exist: $folder", folder.isDirectory)
        return folder
    }

    private fun realArchive(folder: File, entry: ModelEntry): File {
        val file = File(folder, entry.archiveName)
        assertTrue("entry '${entry.id}': the real archive is not in $folder: ${entry.archiveName}", file.isFile)
        return file
    }

    private fun verify(file: File, entry: ModelEntry): ModelIntegrity.Verdict =
        ModelIntegrity.verify(file, entry.sha256, Fixtures.checksumsOf(entry.archiveName to entry.sha256), entry.archiveName)

    @Test
    fun `every real archive is accepted by the integrity check`() {
        val folder = realFolder()
        for (entry in ModelRegistry.ALL) {
            val verdict = verify(realArchive(folder, entry), entry)
            val verified = verdict as? ModelIntegrity.Verdict.Verified
            assertTrue("entry '${entry.id}': the real archive was refused: $verdict", verified != null)
            assertEquals("entry '${entry.id}': the accepted digest is the pin", entry.sha256, verified?.digest)
        }
    }

    @Test
    fun `a real archive with one flipped byte is refused`() {
        val folder = realFolder()
        for (entry in ModelRegistry.ALL) {
            val real = realArchive(folder, entry)
            val copy = File(tmp.newFolder(), entry.archiveName)
            Files.copy(real.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            RandomAccessFile(copy, "rw").use { file ->
                val at = file.length() / 2
                file.seek(at)
                val byte = file.read()
                file.seek(at)
                file.write(byte xor 1)
            }
            val verdict = verify(copy, entry)
            val refused = verdict as? ModelIntegrity.Verdict.Refused
            assertTrue("entry '${entry.id}': the copy with one flipped byte was accepted: $verdict", refused != null)
            assertEquals(
                "entry '${entry.id}': a flipped byte is a digest mismatch",
                ModelIntegrity.Refusal.PIN_MISMATCH,
                refused?.refusal,
            )
            assertTrue("entry '${entry.id}': the original archive was touched", verify(real, entry).isVerified)
            copy.delete()
        }
    }

    private companion object {
        const val DIR_VARIABLE = "BREAKER_REAL_MODELS_DIR"
    }
}
