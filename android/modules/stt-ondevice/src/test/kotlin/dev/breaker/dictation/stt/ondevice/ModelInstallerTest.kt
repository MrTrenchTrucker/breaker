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
        private val modelBytes: ByteArray,
        private val failModel: Boolean = false,
        private val throwModel: Boolean = false,
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            if (throwModel) throw IOException("staging unwritable")
            if (failModel) return ModelFetcher.Result.Failed("network error")
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    @Test
    fun `install succeeds when bytes match the pinned digest and size`() {
        // A real archive: a successful install unpacks what it fetched.
        val bytes = TarFixtures.tinyArchive()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
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
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes, failModel = true)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with DIGEST_REFUSED when bytes do not match pin`() {
        val bytes = "test model bytes".toByteArray()
        val wrongDigest = "0".repeat(64)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", wrongDigest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
    }

    @Test
    fun `install deletes staged file and leaves no model directory when verification fails`() {
        val bytes = "test model bytes".toByteArray()
        val wrongDigest = "0".repeat(64)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", wrongDigest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
        installer.install(entry)
        val stagedFiles = store.stagingDirectory().listFiles() ?: emptyArray()
        assertTrue(stagedFiles.none { it.name == "model.archive" })
        assertFalse(store.directoryFor("tiny").exists())
    }

    @Test
    fun `install refuses with STAGING_FAILED when staging directory cannot be created`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
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
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
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
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
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
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
        val dest = store.archiveFile("tiny")
        dest.mkdirs()
        File(dest, "occupied").writeText("not empty")
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with STAGING_FAILED when model metadata cannot be recorded`() {
        // The step-7 metadata write goes into the model directory, so an
        // IOException there is the same staging-write shape as the fetch.
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
        // A DIRECTORY where the .verified marker must go makes the step-7 write
        // fail with an IOException.
        val modelDir = store.directoryFor("tiny")
        modelDir.mkdirs()
        File(modelDir, LocalModelStore.VERIFIED_FILE).mkdirs()
        val result = installer.install(entry)
        assertFalse(result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
    }

    @Test
    fun `install refuses with STAGING_FAILED when model fetch throws IOException`() {
        // The fetch writes into staging; its IOException is a
        // staging failure and must not escape install().
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
        val fetcher = FakeFetcher(bytes, throwModel = true)
        val installer = ModelInstaller(store, fetcher, usableSpace = { Long.MAX_VALUE })
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
        // The good bytes are a real archive: a successful install unpacks what it fetched.
        val goodBytes = TarFixtures.tinyArchive()
        // Same length as the pinned archive, one byte changed: the size check passes, so the
        // refusal below comes from the digest check.
        val badBytes = goodBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val pin = sha256Hex(goodBytes)
        assertEquals("the bad bytes must have the pinned length", goodBytes.size, badBytes.size)
        assertNotEquals("the bad bytes must not match the pin", pin, sha256Hex(badBytes))
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false, sizeBytes = goodBytes.size.toLong())

        val first = ModelInstaller(store, FakeFetcher(goodBytes), usableSpace = { Long.MAX_VALUE }).install(entry)
        assertTrue(first.isInstalled)
        val archive = store.archiveFile("tiny")
        val archiveBefore = archive.readBytes()
        assertArrayEquals(goodBytes, archiveBefore)
        assertEquals(pin, store.lastVerifiedDigest("tiny"))

        val second = ModelInstaller(store, FakeFetcher(badBytes), usableSpace = { Long.MAX_VALUE }).install(entry)
        assertFalse(second.isInstalled)
        val refused = second as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)

        // The earlier install is exactly as it was.
        assertTrue("the earlier install must still be installed", store.isInstalled("tiny"))
        assertTrue("the earlier archive must still be a file", archive.isFile)
        assertArrayEquals(archiveBefore, archive.readBytes())
        assertEquals(pin, store.lastVerifiedDigest("tiny"))

        // Control: a re-download of a different archive with its own pin is accepted and
        // does replace the archive and the verified digest, so the unchanged state above is
        // a result of the refusal and not of a fixture that cannot change it.
        val otherBytes = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }
        val otherPin = sha256Hex(otherBytes)
        assertNotEquals("fixture: the second archive differs", pin, otherPin)
        val otherEntry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", otherPin, 1, "Apache-2.0", false, sizeBytes = otherBytes.size.toLong())
        val third = ModelInstaller(store, FakeFetcher(otherBytes), usableSpace = { Long.MAX_VALUE }).install(otherEntry)
        assertTrue(third.isInstalled)
        assertEquals(otherPin, (third as ModelInstaller.InstallResult.Installed).digest)
        assertTrue(store.isInstalled("tiny"))
        assertArrayEquals(otherBytes, archive.readBytes())
        assertEquals(otherPin, store.lastVerifiedDigest("tiny"))
    }
}
