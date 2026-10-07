package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Installer behaviour the other installer tests do not look at: a pin written
 * in capitals, a destination that already holds a file on a filesystem whose
 * rename will not replace it, an upstream list that does not name the pin when
 * a model is already installed, the order of the two metadata writes, and a
 * metadata write that fails with an IOException that is not a missing file.
 */
class ModelInstallerEdgeCasesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val goodBytes = "test model bytes".toByteArray()
    private val pin = Fixtures.independentSha256(goodBytes)
    private val listNamingPin = "model.archive\t$pin"

    /** A fetcher that counts its calls and can hand back a wrapped model file. */
    private class StubFetcher(
        private val modelBytes: ByteArray,
        private val checksumsText: String,
        private val wrapModel: (File) -> File = { it },
    ) : ModelFetcher {
        var checksumFetches = 0
        var modelFetches = 0

        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            modelFetches++
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(wrapModel(file))
        }

        override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
            checksumFetches++
            val file = File(stagingDir, "checksums.txt")
            file.writeText(checksumsText)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    private fun entryWithPin(value: String) =
        ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", value, 1, "Apache-2.0", false)

    private fun newStore(removalWorks: Boolean = true): LocalModelStore =
        if (removalWorks) LocalModelStore(tmp.newFolder()) else LocalModelStore(tmp.newFolder(), remove = { false })

    private fun install(store: LocalModelStore, fetcher: ModelFetcher, entry: ModelEntry): ModelInstaller.InstallResult =
        try {
            ModelInstaller(store, fetcher).install(entry)
        } catch (t: Throwable) {
            throw AssertionError("install let ${t.javaClass.name} escape instead of returning a result: $t", t)
        }

    private fun installedOf(result: ModelInstaller.InstallResult): ModelInstaller.InstallResult.Installed {
        assertTrue("expected an install but got $result", result is ModelInstaller.InstallResult.Installed)
        return result as ModelInstaller.InstallResult.Installed
    }

    private fun refusedOf(result: ModelInstaller.InstallResult): ModelInstaller.InstallResult.Refused {
        assertTrue("expected a refusal but got $result", result is ModelInstaller.InstallResult.Refused)
        return result as ModelInstaller.InstallResult.Refused
    }

    /** A staged file whose rename fails when the destination already exists, as on some filesystems. */
    private val noReplaceMove: (File) -> File = { real ->
        object : File(real.path) {
            override fun renameTo(dest: File): Boolean = if (dest.exists()) false else super.renameTo(dest)
        }
    }

    @Test
    fun `a pin written in capitals installs with the computed lowercase digest`() {
        val capitals = pin.uppercase()
        assertNotEquals("fixture: the pin must contain letters", pin, capitals)
        val store = newStore()
        val installed = installedOf(install(store, StubFetcher(goodBytes, listNamingPin), entryWithPin(capitals)))
        assertEquals(pin, installed.digest)
        assertEquals(pin, store.lastVerifiedDigest("tiny"))

        // Control: a lowercase pin gives the same digest and the same record.
        val otherStore = newStore()
        val control = installedOf(install(otherStore, StubFetcher(goodBytes, listNamingPin), entryWithPin(pin)))
        assertEquals(pin, control.digest)
        assertEquals(pin, otherStore.lastVerifiedDigest("tiny"))
    }

    @Test
    fun `an install over an existing archive succeeds on a filesystem whose rename will not replace`() {
        // The fixture really refuses to replace: the staged file cannot be renamed onto a file that exists.
        val probeFrom = tmp.newFile("probe-from")
        val probeTo = tmp.newFile("probe-to")
        assertFalse("fixture: the rename must refuse an existing destination", noReplaceMove(probeFrom).renameTo(probeTo))
        assertTrue(probeFrom.exists())

        val store = newStore()
        Fixtures.writeBytes(store.archiveFile("tiny"), "stale bytes".toByteArray())
        val result = install(store, StubFetcher(goodBytes, listNamingPin, noReplaceMove), entryWithPin(pin))
        assertEquals(pin, installedOf(result).digest)
        assertArrayEquals(goodBytes, store.archiveFile("tiny").readBytes())

        // Control: with no file at the destination the same fetcher installs.
        val fresh = newStore()
        installedOf(install(fresh, StubFetcher(goodBytes, listNamingPin, noReplaceMove), entryWithPin(pin)))
        assertArrayEquals(goodBytes, fresh.archiveFile("tiny").readBytes())
    }

    @Test
    fun `an upstream list that does not name the pin leaves an installed model untouched`() {
        val entry = entryWithPin(pin)
        val store = newStore()
        installedOf(install(store, StubFetcher(goodBytes, listNamingPin), entry))
        val archive = store.archiveFile("tiny")
        val before = archive.readBytes()
        val recordedBefore = store.storedChecksums("tiny")
        assertEquals(listNamingPin, recordedBefore)

        // The same bytes arrive with a list that names another digest.
        val unrelated = Fixtures.independentSha256("other".toByteArray())
        assertNotEquals(pin, unrelated)
        val refused = refusedOf(install(store, StubFetcher(goodBytes, "model.archive\t$unrelated"), entry))
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertTrue("the earlier install must still be installed", store.isInstalled("tiny"))
        assertArrayEquals(before, archive.readBytes())
        assertEquals(recordedBefore, store.storedChecksums("tiny"))
        assertEquals(pin, store.lastVerifiedDigest("tiny"))

        // Control: the same bytes with a list that names the pin are accepted over the earlier install.
        val again = "$listNamingPin\nother.archive\t$unrelated"
        installedOf(install(store, StubFetcher(goodBytes, again), entry))
        assertEquals(again, store.storedChecksums("tiny"))
    }

    @Test
    fun `a file in the place of the staging directory is refused before any fetch`() {
        val fetcher = StubFetcher(goodBytes, listNamingPin)
        val store = newStore()
        File(store.stagingDirectory().path).writeText("blocker")
        val refused = refusedOf(install(store, fetcher, entryWithPin(pin)))
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
        assertEquals("no checksum fetch may start", 0, fetcher.checksumFetches)
        assertEquals("no model fetch may start", 0, fetcher.modelFetches)

        // Control: with nothing in the way the same fetcher is called.
        val freshFetcher = StubFetcher(goodBytes, listNamingPin)
        installedOf(install(newStore(), freshFetcher, entryWithPin(pin)))
        assertEquals(1, freshFetcher.checksumFetches)
        assertEquals(1, freshFetcher.modelFetches)
    }

    @Test
    fun `a failed record write leaves no verified marker when the model cannot be deleted`() {
        // A directory where the checksum record goes makes the first metadata write fail.
        val store = newStore(removalWorks = false)
        val modelDir = store.directoryFor("tiny")
        File(modelDir, LocalModelStore.CHECKSUMS_FILE).mkdirs()
        val refused = refusedOf(install(store, StubFetcher(goodBytes, listNamingPin), entryWithPin(pin)))
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
        assertTrue("the delete failed, so the model is left", refused.leftOnDisk)
        assertFalse("no verified marker may be written before the record", File(modelDir, LocalModelStore.VERIFIED_FILE).exists())
        assertNull(store.lastVerifiedDigest("tiny"))
    }

    @Test
    fun `a failed marker write leaves the checksum record when the model cannot be deleted`() {
        // A directory where the verified marker goes makes the second metadata write fail.
        val store = newStore(removalWorks = false)
        val modelDir = store.directoryFor("tiny")
        File(modelDir, LocalModelStore.VERIFIED_FILE).mkdirs()
        val refused = refusedOf(install(store, StubFetcher(goodBytes, listNamingPin), entryWithPin(pin)))
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
        assertTrue("the delete failed, so the model is left", refused.leftOnDisk)
        assertEquals("the record is written before the marker", listNamingPin, store.storedChecksums("tiny"))
    }

    @Test
    fun `a metadata write that fails with an IOException that is not a missing file is refused`() {
        // A checksum record that is a link to a device whose writes always fail with a full disk.
        val full = File("/dev/full")
        assumeTrue("needs a Linux device that fails every write", System.getProperty("os.name").startsWith("Linux") && full.exists())
        val store = newStore()
        val modelDir = store.directoryFor("tiny")
        modelDir.mkdirs()
        val linked = try {
            Files.createSymbolicLink(File(modelDir, LocalModelStore.CHECKSUMS_FILE).toPath(), full.toPath())
            true
        } catch (e: IOException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        }
        assumeTrue("needs symbolic links", linked)
        // Control: writing to that link fails with an IOException and not with a missing file.
        val failure: IOException? = try {
            File(modelDir, LocalModelStore.CHECKSUMS_FILE).writeText("x")
            null
        } catch (e: IOException) {
            e
        }
        assertTrue("fixture: the write must fail", failure != null)
        assertFalse("fixture: the failure must not be a missing file", failure is java.io.FileNotFoundException)

        val refused = refusedOf(install(store, StubFetcher(goodBytes, listNamingPin), entryWithPin(pin)))
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, refused.detail)
        assertFalse("the model was deleted", store.directoryFor("tiny").exists())
    }
}
