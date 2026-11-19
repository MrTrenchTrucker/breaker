package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The technical text the installer sends to the debug sink for each refusal,
 * with the fixed sentence the user gets beside it. The sink text is for a
 * developer, so it is held exactly: "install refused (NAME): " and then what
 * went wrong, with the path or the exception text in it.
 */
class ModelInstallerSinkTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val goodBytes = "test model bytes".toByteArray()
    private val pin = Fixtures.independentSha256(goodBytes)

    /** The pinned length with one byte changed: the size passes, so the digest refuses it. */
    private val wrongBytes = goodBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
    private val wrongDigest = Fixtures.independentSha256(wrongBytes)

    /** One byte longer than the pinned length: the size refuses it. */
    private val longerBytes = goodBytes + byteArrayOf(1)
    private val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false, sizeBytes = goodBytes.size.toLong())

    /** A fetcher that can throw, fail or hand back a wrapped file at the model step. */
    private class StubFetcher(
        private val modelBytes: ByteArray,
        private val modelThrow: String? = null,
        private val modelFailure: String? = null,
        private val wrapModel: (File) -> File = { it },
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            if (modelThrow != null) throw IOException(modelThrow)
            if (modelFailure != null) return ModelFetcher.Result.Failed(modelFailure)
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(wrapModel(file))
        }
    }

    /** One refused install: the result, every sink message, and the store it ran on. */
    private class Outcome(
        val refused: ModelInstaller.InstallResult.Refused,
        val sunk: List<String>,
        val store: LocalModelStore,
    )

    private fun goodFetcher() = StubFetcher(goodBytes)

    /** Runs one install and requires a refusal; an exception that escapes fails the test. */
    private fun attempt(
        fetcher: ModelFetcher,
        store: LocalModelStore = LocalModelStore(tmp.newFolder()),
        usableSpace: (File) -> Long = { Long.MAX_VALUE },
        prepare: (LocalModelStore) -> Unit = {},
    ): Outcome {
        prepare(store)
        val sunk = ArrayList<String>()
        val result = try {
            ModelInstaller(store, fetcher, ModelDebugSink { message -> sunk.add(message) }, usableSpace = usableSpace).install(entry)
        } catch (t: Throwable) {
            throw AssertionError("install let ${t.javaClass.name} escape instead of returning a result: $t", t)
        }
        assertTrue("the install must be refused, got $result", result is ModelInstaller.InstallResult.Refused)
        return Outcome(result as ModelInstaller.InstallResult.Refused, sunk, store)
    }

    private fun assertRefused(
        outcome: Outcome,
        refusal: ModelInstaller.Refusal,
        sentence: String,
        technical: String,
    ) {
        assertEquals(refusal, outcome.refused.refusal)
        assertEquals(sentence, outcome.refused.detail)
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertEquals("install refused (${refusal.name}): $technical", outcome.sunk.single())
    }

    /** A fetcher whose staged model file cannot be moved. */
    private val unmovable: (File) -> File = { real ->
        object : File(real.path) {
            override fun renameTo(dest: File): Boolean = false
        }
    }

    /** A fetcher whose move puts different bytes at the destination, so the final check refuses. */
    private val swapsOnMove: (File) -> File = { real ->
        object : File(real.path) {
            override fun renameTo(dest: File): Boolean {
                dest.writeBytes(wrongBytes)
                return true
            }
        }
    }

    @Test
    fun `a file in the place of the staging directory is named in the sink`() {
        val outcome = attempt(goodFetcher()) { store -> File(store.stagingDirectory().path).writeText("blocker") }
        val staging = outcome.store.stagingDirectory().absolutePath
        assertRefused(outcome, ModelInstaller.Refusal.STAGING_FAILED, ModelMessages.SAVE_FAILED, "not a directory: $staging")
    }

    @Test
    fun `a staging directory that cannot be created is named in the sink`() {
        // The models root is a file, so nothing can be created below it.
        val outcome = attempt(goodFetcher(), LocalModelStore(tmp.newFile("root-is-a-file")))
        val staging = outcome.store.stagingDirectory().absolutePath
        assertRefused(outcome, ModelInstaller.Refusal.STAGING_FAILED, ModelMessages.SAVE_FAILED, "cannot create $staging")
    }

    @Test
    fun `a fetch that throws names the staging path and the exception text in the sink`() {
        val modelStep = attempt(StubFetcher(goodBytes, modelThrow = "model-detail"))
        val modelStaging = modelStep.store.stagingDirectory().absolutePath
        assertRefused(
            modelStep,
            ModelInstaller.Refusal.STAGING_FAILED,
            ModelMessages.SAVE_FAILED,
            "cannot write into $modelStaging: model-detail",
        )
    }

    @Test
    fun `a move that fails names both paths in the sink`() {
        val outcome = attempt(StubFetcher(goodBytes, wrapModel = unmovable))
        val staged = File(outcome.store.stagingDirectory(), "model.archive").absolutePath
        val archive = outcome.store.archiveFile("tiny").absolutePath
        assertRefused(outcome, ModelInstaller.Refusal.MOVE_FAILED, ModelMessages.SAVE_FAILED, "cannot move $staged to $archive")
    }

    @Test
    fun `a metadata write that fails sends the failure text to the sink`() {
        val outcome = attempt(goodFetcher()) { store ->
            store.directoryFor("tiny").mkdirs()
            File(store.directoryFor("tiny"), LocalModelStore.VERIFIED_FILE).mkdirs()
        }
        val expectedStart = "install refused (STAGING_FAILED): cannot record model metadata: "
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, outcome.refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, outcome.refused.detail)
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        val sunk = outcome.sunk.single()
        assertTrue("the sink text must start with '$expectedStart': $sunk", sunk.startsWith(expectedStart))
        assertTrue("the failure text must name the file: $sunk", sunk.substring(expectedStart.length).contains(LocalModelStore.VERIFIED_FILE))
    }

    @Test
    fun `too little space names the need and the free bytes in the sink`() {
        // tiny's unpack profile lets the unpack write 80 MiB; the pinned archive is 16 bytes.
        val unpacked = 80L * 1_048_576L
        val outcome = attempt(goodFetcher(), usableSpace = { 0L })
        val staging = outcome.store.stagingDirectory().absolutePath
        assertRefused(
            outcome,
            ModelInstaller.Refusal.NO_SPACE,
            ModelMessages.DOWNLOAD_NO_SPACE,
            "need ${goodBytes.size + unpacked} bytes (archive ${goodBytes.size} + unpacked $unpacked), free 0 in $staging",
        )
    }

    @Test
    fun `the other refusals send their reason after the refusal name`() {
        assertRefused(
            attempt(StubFetcher(goodBytes, modelFailure = "mirror-down")),
            ModelInstaller.Refusal.FETCH_FAILED,
            ModelMessages.MODEL_DOWNLOAD_FAILED,
            "mirror-down",
        )
        assertRefused(
            attempt(StubFetcher(wrongBytes)),
            ModelInstaller.Refusal.DIGEST_REFUSED,
            ModelMessages.DOWNLOAD_FAILED_CHECK,
            "PIN_MISMATCH: expected $pin, found $wrongDigest",
        )
        assertRefused(
            attempt(StubFetcher(longerBytes)),
            ModelInstaller.Refusal.DIGEST_REFUSED,
            ModelMessages.DOWNLOAD_FAILED_CHECK,
            "SIZE_MISMATCH: expected ${goodBytes.size} bytes, found ${longerBytes.size}",
        )
        assertRefused(
            attempt(StubFetcher(goodBytes, wrapModel = swapsOnMove)),
            ModelInstaller.Refusal.DIGEST_REFUSED,
            ModelMessages.DOWNLOAD_FAILED_CHECK,
            "PIN_MISMATCH: expected $pin, found $wrongDigest",
        )

        val blockedModelDir = attempt(goodFetcher()) { store -> File(store.directoryFor("tiny").path).writeText("blocker") }
        assertRefused(
            blockedModelDir,
            ModelInstaller.Refusal.STAGING_FAILED,
            ModelMessages.SAVE_FAILED,
            "cannot create ${blockedModelDir.store.directoryFor("tiny").absolutePath}",
        )

        val blockedArchive = attempt(goodFetcher()) { store -> store.archiveFile("tiny").mkdirs() }
        assertRefused(
            blockedArchive,
            ModelInstaller.Refusal.MOVE_FAILED,
            ModelMessages.SAVE_FAILED,
            "destination is not a file: ${blockedArchive.store.archiveFile("tiny").absolutePath}",
        )
    }
}
