package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.root)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class FakeFetcher(
        private val modelBytes: ByteArray?,
        private val checksumsText: String?,
        private val failModel: Boolean = false,
        private val failChecksums: Boolean = false,
        private val throwModel: Boolean = false,
        private val throwChecksums: Boolean = false,
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            if (throwModel) throw IOException("staging unwritable")
            if (failModel) return ModelFetcher.Result.Failed("network error")
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes!!)
            return ModelFetcher.Result.Fetched(file)
        }
        override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
            if (throwChecksums) throw IOException("staging unwritable")
            if (failChecksums) return ModelFetcher.Result.Failed("network error")
            val file = File(stagingDir, "checksums.txt")
            file.writeText(checksumsText!!)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    @Test
    fun `install succeeds when bytes match pin and checksums contain pin`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$digest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        val result = installer.install(entry)
        assertTrue(result.isInstalled)
        val installed = result as ModelInstaller.InstallResult.Installed
        assertEquals("tiny", installed.modelId)
        assertEquals(digest, installed.digest)
    }

    @Test
    fun `install refuses with FETCH_FAILED when model fetch fails`() {
        val bytes = "test".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val fetcher = FakeFetcher(bytes, "model.archive\t$digest", failModel = true)
        val installer = ModelInstaller(store, fetcher)
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with CHECKSUMS_FAILED when checksums fetch fails`() {
        val bytes = "test".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val fetcher = FakeFetcher(bytes, "model.archive\t$digest", failChecksums = true)
        val installer = ModelInstaller(store, fetcher)
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.CHECKSUMS_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with CHECKSUMS_UNREADABLE when checksums are unparseable`() {
        val bytes = "test".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val fetcher = FakeFetcher(bytes, "not a valid checksums file")
        val installer = ModelInstaller(store, fetcher)
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.CHECKSUMS_UNREADABLE, refused.refusal)
    }

    @Test
    fun `install refuses with DIGEST_REFUSED when bytes do not match pin`() {
        val bytes = "test model bytes".toByteArray()
        val wrongDigest = "0".repeat(64)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", wrongDigest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$wrongDigest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
    }

    @Test
    fun `install deletes staged file and leaves no model directory when verification fails`() {
        val bytes = "test model bytes".toByteArray()
        val wrongDigest = "0".repeat(64)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", wrongDigest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$wrongDigest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        installer.install(entry)
        val stagedFiles = store.stagingDirectory().listFiles() ?: emptyArray()
        assertTrue(stagedFiles.none { it.name == "model.archive" })
        assertFalse(store.directoryFor("tiny").exists())
    }

    @Test
    fun `install refuses with STAGING_FAILED when staging directory cannot be created`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$digest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        File(store.stagingDirectory().path).writeText("blocker")
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with STAGING_FAILED when model directory cannot be created`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$digest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        File(store.directoryFor("tiny").path).writeText("blocker")
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with MOVE_FAILED when archive destination is a directory`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$digest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        store.archiveFile("tiny").mkdirs()
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with MOVE_FAILED when archive destination is a NON-empty directory`() {
        // A non-empty directory is the case delete() cannot clear, so this
        // proves the refusal comes from the explicit non-file guard and not
        // from delete() happening to fail.
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$digest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        val dest = store.archiveFile("tiny")
        dest.mkdirs()
        File(dest, "occupied").writeText("not empty")
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with STAGING_FAILED when checksums fetch throws IOException`() {
        // The fetcher writes into staging, so an IOException from the write is
        // a staging failure and must be returned, never escape install().
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val fetcher = FakeFetcher(bytes, null, throwChecksums = true)
        val installer = ModelInstaller(store, fetcher)
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with STAGING_FAILED when model metadata cannot be recorded`() {
        // The step-7 metadata writes go into the model directory, so an
        // IOException there is the same staging-write shape as the fetches.
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$digest"
        val fetcher = FakeFetcher(bytes, checksumsText)
        val installer = ModelInstaller(store, fetcher)
        // A DIRECTORY where the .checksums file must go makes the step-7 write
        // fail with an IOException.
        val modelDir = store.directoryFor("tiny")
        modelDir.mkdirs()
        File(modelDir, LocalModelStore.CHECKSUMS_FILE).mkdirs()
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with STAGING_FAILED when model fetch throws IOException`() {
        // The second fetch also writes into staging; its IOException is a
        // staging failure too and must not escape install().
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val checksumsText = "model.archive\t$digest"
        val fetcher = FakeFetcher(bytes, checksumsText, throwModel = true)
        val installer = ModelInstaller(store, fetcher)
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses a bad re-download and leaves the earlier install untouched`() {
        // A re-download is verified in staging before it touches the model
        // directory, so a bad one cannot destroy a model that is already
        // installed and verified.
        val goodBytes = "test model bytes".toByteArray()
        val badBytes = "different model bytes".toByteArray()
        val pin = sha256Hex(goodBytes)
        assertNotEquals("the bad bytes must not match the pin", pin, sha256Hex(badBytes))
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false)
        // Both lists name the pin. The second also names another archive, so a
        // checksum record rewritten by the second install would differ from the
        // one the first install stored.
        val firstChecksums = "model.archive\t$pin"
        val secondChecksums = "model.archive\t$pin\nother.archive\t${sha256Hex("other".toByteArray())}"

        val first = ModelInstaller(store, FakeFetcher(goodBytes, firstChecksums)).install(entry)
        assertTrue(first.isInstalled)
        val archive = store.archiveFile("tiny")
        val archiveBefore = archive.readBytes()
        assertArrayEquals(goodBytes, archiveBefore)
        assertEquals(firstChecksums, store.storedChecksums("tiny"))
        assertEquals(pin, store.lastVerifiedDigest("tiny"))

        val second = ModelInstaller(store, FakeFetcher(badBytes, secondChecksums)).install(entry)
        assertFalse(second.isInstalled)
        val refused = second as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)

        // The earlier install is exactly as it was.
        assertTrue("the earlier install must still be installed", store.isInstalled("tiny"))
        assertTrue("the earlier archive must still be a file", archive.isFile)
        assertArrayEquals(archiveBefore, archive.readBytes())
        assertEquals(firstChecksums, store.storedChecksums("tiny"))
        assertEquals(pin, store.lastVerifiedDigest("tiny"))

        // Control: the same re-download with the good bytes is accepted and
        // does rewrite the checksum record, so the unchanged record above is
        // a result of the refusal and not of a fixture that cannot change it.
        val third = ModelInstaller(store, FakeFetcher(goodBytes, secondChecksums)).install(entry)
        assertTrue(third.isInstalled)
        assertEquals(pin, (third as ModelInstaller.InstallResult.Installed).digest)
        assertTrue(store.isInstalled("tiny"))
        assertArrayEquals(goodBytes, archive.readBytes())
        assertEquals(secondChecksums, store.storedChecksums("tiny"))
        assertEquals(pin, store.lastVerifiedDigest("tiny"))
    }

    @Test
    fun `install refuses bytes that match the pin when the fetched upstream list does not name the pin`() {
        val bytes = "test model bytes".toByteArray()
        val pin = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false)
        val unrelated = sha256Hex("other".toByteArray())
        assertNotEquals(pin, unrelated)

        val refusedResult = ModelInstaller(store, FakeFetcher(bytes, "model.archive\t$unrelated")).install(entry)
        assertFalse("bytes that match the pin must not install when upstream does not name it", refusedResult.isInstalled)
        val refused = refusedResult as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertFalse(store.isInstalled("tiny"))
        assertFalse(store.directoryFor("tiny").exists())

        // Control: the same bytes with an upstream list that names the pin.
        val accepted = ModelInstaller(store, FakeFetcher(bytes, "model.archive\t$pin")).install(entry)
        assertTrue(accepted.isInstalled)
        assertTrue(store.isInstalled("tiny"))
    }
}
