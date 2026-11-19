package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * What a user sees when an install is refused: one fixed sentence per kind of
 * problem, with the technical text (paths, exception messages, fetch reasons,
 * the integrity verdict) delivered to the debug sink instead.
 */
class ModelInstallerMessagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fetcher that can fail or throw at the model step, with its own bytes. */
    private class StubFetcher(
        private val modelBytes: ByteArray,
        private val modelFailure: String? = null,
        private val modelThrow: String? = null,
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            if (modelThrow != null) throw IOException(modelThrow)
            if (modelFailure != null) return ModelFetcher.Result.Failed(modelFailure)
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    /** One refused install: the result, every message the sink received, and the store it ran on. */
    private class Outcome(
        val refused: ModelInstaller.InstallResult.Refused,
        val sunk: List<String>,
        val store: LocalModelStore,
    )

    private val goodBytes = "test model bytes".toByteArray()
    private val pin = Fixtures.independentSha256(goodBytes)
    private val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false, sizeBytes = goodBytes.size.toLong())

    private fun goodFetcher() = StubFetcher(goodBytes)

    /** Runs one install on a fresh store with a recording sink and requires it to be refused. */
    private fun attempt(
        fetcher: ModelFetcher,
        usableSpace: (File) -> Long = { Long.MAX_VALUE },
        prepare: (LocalModelStore) -> Unit = {},
    ): Outcome {
        val store = LocalModelStore(tmp.newFolder())
        prepare(store)
        val sunk = mutableListOf<String>()
        val result = ModelInstaller(store, fetcher, ModelDebugSink { message -> sunk.add(message) }, usableSpace = usableSpace).install(entry)
        assertTrue("the install must be refused, got $result", result is ModelInstaller.InstallResult.Refused)
        return Outcome(result as ModelInstaller.InstallResult.Refused, sunk, store)
    }

    private fun stagingBlocked() = attempt(goodFetcher()) { store ->
        File(store.stagingDirectory().path).writeText("blocker")
    }

    private fun modelDownloadFails(reason: String) =
        attempt(StubFetcher(goodBytes, modelFailure = reason))

    /** The pinned length with one byte changed: the size passes, so the digest refuses it. */
    private fun wrongBytes() =
        attempt(StubFetcher(goodBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }))

    /** One byte longer than the pinned length: the size refuses it. */
    private fun wrongSize() =
        attempt(StubFetcher(goodBytes + byteArrayOf(1)))

    private fun modelFetchThrows(message: String) =
        attempt(StubFetcher(goodBytes, modelThrow = message))

    private fun noSpace() = attempt(goodFetcher(), usableSpace = { 0L })

    private fun moveBlocked() = attempt(goodFetcher()) { store ->
        store.archiveFile("tiny").mkdirs()
    }

    private fun assertNoSeparator(label: String, detail: String) {
        assertFalse("$label: the detail holds a path separator: $detail", detail.contains('/') || detail.contains('\\'))
    }

    @Test
    fun `a failed staging directory gives the save sentence and sends the path to the sink only`() {
        val outcome = stagingBlocked()
        val stagingPath = outcome.store.stagingDirectory().absolutePath
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, outcome.refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, outcome.refused.detail)
        assertNoSeparator("staging", outcome.refused.detail)
        assertFalse("the path must not reach the user", outcome.refused.detail.contains(stagingPath))
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the staging path: ${outcome.sunk}", outcome.sunk.single().contains(stagingPath))
    }

    @Test
    fun `a failed model download gives the download sentence and the reason goes to the sink`() {
        val outcome = modelDownloadFails("other-reason")
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, outcome.refused.refusal)
        assertEquals(ModelMessages.MODEL_DOWNLOAD_FAILED, outcome.refused.detail)
        assertFalse(outcome.refused.detail.contains("other-reason"))
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the reason: ${outcome.sunk}", outcome.sunk.single().contains("other-reason"))
    }

    @Test
    fun `a download that fails its check gives the check sentence and the verdict goes to the sink`() {
        val outcome = wrongBytes()
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, outcome.refused.refusal)
        assertEquals(ModelMessages.DOWNLOAD_FAILED_CHECK, outcome.refused.detail)
        assertFalse("the digest must not reach the user", outcome.refused.detail.contains(pin))
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the verdict name: ${outcome.sunk}", outcome.sunk.single().contains("PIN_MISMATCH"))
    }

    @Test
    fun `a download of the wrong size gives the check sentence and the size verdict goes to the sink`() {
        val outcome = wrongSize()
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, outcome.refused.refusal)
        assertEquals(ModelMessages.DOWNLOAD_FAILED_CHECK, outcome.refused.detail)
        assertFalse("the sizes must not reach the user", outcome.refused.detail.contains(goodBytes.size.toString()))
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the verdict name: ${outcome.sunk}", outcome.sunk.single().contains("SIZE_MISMATCH"))
    }

    @Test
    fun `too little space gives the no-space sentence and the figures go to the sink only`() {
        val outcome = noSpace()
        assertEquals(ModelInstaller.Refusal.NO_SPACE, outcome.refused.refusal)
        assertEquals(ModelMessages.DOWNLOAD_NO_SPACE, outcome.refused.detail)
        assertNoSeparator("no space", outcome.refused.detail)
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the free figure: ${outcome.sunk}", outcome.sunk.single().contains("free 0 in"))
    }

    @Test
    fun `a fetcher that throws an IOException gives the save sentence and the exception text goes to the sink only`() {
        val modelStep = modelFetchThrows("disk-detail-model")
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, modelStep.refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, modelStep.refused.detail)
        assertFalse(modelStep.refused.detail.contains("disk-detail-model"))
        assertFalse(modelStep.refused.detail.contains("IOException"))
        assertEquals("one sink call per refusal", 1, modelStep.sunk.size)
        assertTrue("the sink must hold the exception text: ${modelStep.sunk}", modelStep.sunk.single().contains("disk-detail-model"))
    }

    @Test
    fun `a move that fails gives the save sentence`() {
        val outcome = moveBlocked()
        val archivePath = outcome.store.archiveFile("tiny").absolutePath
        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, outcome.refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, outcome.refused.detail)
        assertNoSeparator("move", outcome.refused.detail)
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the archive path: ${outcome.sunk}", outcome.sunk.single().contains(archivePath))
    }

    @Test
    fun `a model directory that cannot be created gives the save sentence and sends the path to the sink only`() {
        val outcome = attempt(goodFetcher()) { store -> File(store.directoryFor("tiny").path).writeText("blocker") }
        val modelPath = outcome.store.directoryFor("tiny").absolutePath
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, outcome.refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, outcome.refused.detail)
        assertFalse(outcome.refused.detail.contains(modelPath))
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the model directory path: ${outcome.sunk}", outcome.sunk.single().contains(modelPath))
    }

    @Test
    fun `a metadata write that fails gives the save sentence and sends the exception text to the sink only`() {
        // A directory where the verified marker must go makes the metadata write throw.
        val outcome = attempt(goodFetcher()) { store ->
            store.directoryFor("tiny").mkdirs()
            File(store.directoryFor("tiny"), LocalModelStore.VERIFIED_FILE).mkdirs()
        }
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, outcome.refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, outcome.refused.detail)
        assertNoSeparator("metadata", outcome.refused.detail)
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must hold the metadata failure: ${outcome.sunk}", outcome.sunk.single().contains("cannot record model metadata"))
    }

    @Test
    fun `no refusal detail of the installer holds a path separator or an exception name`() {
        val sentences = setOf(
            ModelMessages.SAVE_FAILED,
            ModelMessages.MODEL_DOWNLOAD_FAILED,
            ModelMessages.DOWNLOAD_FAILED_CHECK,
            ModelMessages.DOWNLOAD_NO_SPACE,
        )
        // Fetch reasons and exception messages that look like the real thing: a path, a class name.
        val cases = listOf(
            "staging" to stagingBlocked(),
            "model download" to modelDownloadFails("HTTP 500 from C:\\temp\\mirror: ServerError"),
            "check" to wrongBytes(),
            "size" to wrongSize(),
            "fetcher exception" to modelFetchThrows("failed writing /data/app/staging/model.archive: IOException"),
            "move" to moveBlocked(),
            "no space" to noSpace(),
        )
        assertEquals("every refusal kind must be built", 7, cases.size)
        var checked = 0
        for ((label, outcome) in cases) {
            val detail = outcome.refused.detail
            assertNoSeparator(label, detail)
            assertFalse("$label: the detail names an exception: $detail", detail.contains("Exception") || detail.contains("Error"))
            assertTrue("$label: the detail is not one of the fixed sentences: $detail", detail in sentences)
            checked++
        }
        assertEquals("every case must have been checked", 7, checked)
    }

    @Test
    fun `the default sink drops the technical text and the installer still refuses`() {
        val store = LocalModelStore(tmp.newFolder())
        File(store.stagingDirectory().path).writeText("blocker")
        val result = ModelInstaller(store, goodFetcher(), usableSpace = { Long.MAX_VALUE }).install(entry)
        assertTrue("the install must be refused, got $result", result is ModelInstaller.InstallResult.Refused)
        assertEquals(ModelMessages.SAVE_FAILED, (result as ModelInstaller.InstallResult.Refused).detail)

        // Control: the same fixture with a recording sink is refused with the same sentence
        // and does send technical text, so the refusal above is not a fixture that cannot reach the sink.
        val recorded = stagingBlocked()
        assertEquals(ModelMessages.SAVE_FAILED, recorded.refused.detail)
        assertTrue(recorded.sunk.isNotEmpty())
    }
}
