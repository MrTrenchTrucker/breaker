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
    private val wrongBytes = "different model bytes".toByteArray()
    private val wrongDigest = Fixtures.independentSha256(wrongBytes)
    private val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false)

    /** A fetcher that can throw, fail or hand back a wrapped file at either step. */
    private class StubFetcher(
        private val modelBytes: ByteArray,
        private val checksumsText: String,
        private val checksumsThrow: String? = null,
        private val modelThrow: String? = null,
        private val checksumsFailure: String? = null,
        private val modelFailure: String? = null,
        private val wrapChecksums: (File) -> File = { it },
        private val wrapModel: (File) -> File = { it },
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            if (modelThrow != null) throw IOException(modelThrow)
            if (modelFailure != null) return ModelFetcher.Result.Failed(modelFailure)
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(wrapModel(file))
        }

        override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
            if (checksumsThrow != null) throw IOException(checksumsThrow)
            if (checksumsFailure != null) return ModelFetcher.Result.Failed(checksumsFailure)
            val file = File(stagingDir, "checksums.txt")
            file.writeText(checksumsText)
            return ModelFetcher.Result.Fetched(wrapChecksums(file))
        }
    }

    /** One refused install: the result, every sink message, and the store it ran on. */
    private class Outcome(
        val refused: ModelInstaller.InstallResult.Refused,
        val sunk: List<String>,
        val store: LocalModelStore,
    )

    private fun goodFetcher() = StubFetcher(goodBytes, "model.archive\t$pin")

    /** Runs one install and requires a refusal; an exception that escapes fails the test. */
    private fun attempt(
        fetcher: ModelFetcher,
        store: LocalModelStore = LocalModelStore(tmp.newFolder()),
        prepare: (LocalModelStore) -> Unit = {},
    ): Outcome {
        prepare(store)
        val sunk = ArrayList<String>()
        val result = try {
            ModelInstaller(store, fetcher, ModelDebugSink { message -> sunk.add(message) }).install(entry)
        } catch (t: Throwable) {
            throw AssertionError("install let ${t.javaClass.name} escape instead of returning a result: $t", t)
        }
        assertTrue("the install must be refused, got $result", result is ModelInstaller.InstallResult.Refused)
        return Outcome(result as ModelInstaller.InstallResult.Refused, sunk, store)
    }

    /** The text the parser gives for [text]; the test fails if the parser accepts it. */
    private fun parserMessage(text: String): String {
        try {
            UpstreamChecksums.parse(text)
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            return e.message ?: ""
        }
        throw AssertionError("the parser accepted: $text")
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
        val checksumsStep = attempt(StubFetcher(goodBytes, "", checksumsThrow = "disk-detail"))
        val staging = checksumsStep.store.stagingDirectory().absolutePath
        assertRefused(
            checksumsStep,
            ModelInstaller.Refusal.STAGING_FAILED,
            ModelMessages.SAVE_FAILED,
            "cannot write into $staging: disk-detail",
        )

        val modelStep = attempt(StubFetcher(goodBytes, "model.archive\t$pin", modelThrow = "model-detail"))
        val modelStaging = modelStep.store.stagingDirectory().absolutePath
        assertRefused(
            modelStep,
            ModelInstaller.Refusal.STAGING_FAILED,
            ModelMessages.SAVE_FAILED,
            "cannot write into $modelStaging: model-detail",
        )
    }

    @Test
    fun `a checksum file that cannot be read sends the exception text to the sink and does not escape`() {
        // Reading the file fails with an exception that is not an IOException.
        val unreadable: (File) -> File = { real ->
            object : File(real.path) {
                override fun getPath(): String = throw IllegalStateException("unreadable-detail")
            }
        }
        val outcome = attempt(StubFetcher(goodBytes, "model.archive\t$pin", wrapChecksums = unreadable))
        assertRefused(
            outcome,
            ModelInstaller.Refusal.CHECKSUMS_UNREADABLE,
            ModelMessages.CHECKSUMS_UNREADABLE_DOWNLOADED,
            "checksum file unreadable: unreadable-detail",
        )
    }

    @Test
    fun `a checksum file that is a directory sends the read failure text to the sink`() {
        val asDirectory: (File) -> File = { real -> real.parentFile }
        val outcome = attempt(StubFetcher(goodBytes, "model.archive\t$pin", wrapChecksums = asDirectory))
        val expectedStart = "install refused (CHECKSUMS_UNREADABLE): checksum file unreadable: "
        assertEquals(ModelInstaller.Refusal.CHECKSUMS_UNREADABLE, outcome.refused.refusal)
        assertEquals(ModelMessages.CHECKSUMS_UNREADABLE_DOWNLOADED, outcome.refused.detail)
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        val sunk = outcome.sunk.single()
        assertTrue("the sink text must start with '$expectedStart': $sunk", sunk.startsWith(expectedStart))
        assertTrue("the read failure text must follow: $sunk", sunk.length > expectedStart.length)
    }

    @Test
    fun `a checksum file that cannot be parsed sends the parser text to the sink`() {
        val garbage = "not a valid checksums file"
        val parserText = parserMessage(garbage)
        assertTrue("fixture: the parser must say something", parserText.isNotEmpty())
        val outcome = attempt(StubFetcher(goodBytes, garbage))
        assertRefused(
            outcome,
            ModelInstaller.Refusal.CHECKSUMS_UNREADABLE,
            ModelMessages.CHECKSUMS_UNREADABLE_DOWNLOADED,
            "checksum file unparseable: $parserText",
        )
    }

    @Test
    fun `a move that fails names both paths in the sink`() {
        val outcome = attempt(StubFetcher(goodBytes, "model.archive\t$pin", wrapModel = unmovable))
        val staged = File(outcome.store.stagingDirectory(), "model.archive").absolutePath
        val archive = outcome.store.archiveFile("tiny").absolutePath
        assertRefused(outcome, ModelInstaller.Refusal.MOVE_FAILED, ModelMessages.SAVE_FAILED, "cannot move $staged to $archive")
    }

    @Test
    fun `a metadata write that fails sends the failure text to the sink`() {
        val outcome = attempt(goodFetcher()) { store ->
            store.directoryFor("tiny").mkdirs()
            File(store.directoryFor("tiny"), LocalModelStore.CHECKSUMS_FILE).mkdirs()
        }
        val expectedStart = "install refused (STAGING_FAILED): cannot record model metadata: "
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, outcome.refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, outcome.refused.detail)
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        val sunk = outcome.sunk.single()
        assertTrue("the sink text must start with '$expectedStart': $sunk", sunk.startsWith(expectedStart))
        assertTrue("the failure text must name the file: $sunk", sunk.substring(expectedStart.length).contains(LocalModelStore.CHECKSUMS_FILE))
    }

    @Test
    fun `the other refusals send their reason after the refusal name`() {
        assertRefused(
            attempt(StubFetcher(goodBytes, "", checksumsFailure = "net-down")),
            ModelInstaller.Refusal.CHECKSUMS_FAILED,
            ModelMessages.CHECKSUMS_DOWNLOAD_FAILED,
            "net-down",
        )
        assertRefused(
            attempt(StubFetcher(goodBytes, "model.archive\t$pin", modelFailure = "mirror-down")),
            ModelInstaller.Refusal.FETCH_FAILED,
            ModelMessages.MODEL_DOWNLOAD_FAILED,
            "mirror-down",
        )
        assertRefused(
            attempt(StubFetcher(wrongBytes, "model.archive\t$pin")),
            ModelInstaller.Refusal.DIGEST_REFUSED,
            ModelMessages.DOWNLOAD_FAILED_CHECK,
            "PIN_MISMATCH: expected $pin, found $wrongDigest",
        )
        assertRefused(
            attempt(StubFetcher(goodBytes, "model.archive\t$pin", wrapModel = swapsOnMove)),
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
