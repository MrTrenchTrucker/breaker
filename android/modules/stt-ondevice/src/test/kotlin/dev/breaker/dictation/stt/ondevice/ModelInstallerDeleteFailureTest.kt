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

/**
 * What the installer reports when a file it rejected cannot be deleted: the
 * refusal and its category stay, the sentence adds that the file could not be
 * deleted, the result says the file is still on disk, and the debug sink gets
 * one message that notes the failed delete.
 */
class ModelInstallerDeleteFailureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val goodBytes = "test model bytes".toByteArray()
    private val pin = Fixtures.independentSha256(goodBytes)
    private val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false)
    private val couldNotDelete = "The file could not be deleted."

    /**
     * A fetcher that writes [modelBytes] and a checksum list naming the pin.
     * [wrap] can return a [File] subclass in place of the staged file, so a
     * test can decide what the move does.
     */
    private class StubFetcher(
        private val modelBytes: ByteArray,
        private val pin: String,
        private val wrap: (File) -> File = { it },
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(wrap(file))
        }

        override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "checksums.txt")
            file.writeText("model.archive\t$pin")
            return ModelFetcher.Result.Fetched(file)
        }
    }

    /** One refused install: the result, every sink message, and the store it ran on. */
    private class Outcome(
        val refused: ModelInstaller.InstallResult.Refused,
        val sunk: List<String>,
        val store: LocalModelStore,
    )

    /** Which delete of the store fails: none, the discard of a staged file, or the delete of a model directory. */
    private enum class Failing { NONE, DISCARD, DELETE }

    /** Runs one install on a fresh store where the chosen delete fails, and requires a refusal. */
    private fun attempt(
        failing: Failing,
        fetcher: ModelFetcher,
        prepare: (LocalModelStore) -> Unit = {},
    ): Outcome {
        val root = tmp.newFolder()
        val store = when (failing) {
            Failing.NONE -> LocalModelStore(root)
            Failing.DISCARD -> LocalModelStore(root, discard = { false })
            Failing.DELETE -> LocalModelStore(root, remove = { false })
        }
        prepare(store)
        val sunk = mutableListOf<String>()
        val result = ModelInstaller(store, fetcher, ModelDebugSink { sunk.add(it) }).install(entry)
        assertTrue("the install must be refused, got $result", result is ModelInstaller.InstallResult.Refused)
        return Outcome(result as ModelInstaller.InstallResult.Refused, sunk, store)
    }

    private fun stagedArchive(outcome: Outcome) = File(outcome.store.stagingDirectory(), "model.archive")

    /**
     * The refusal, its whole sentence, the flag and the sink note, for a refusal whose delete failed.
     * [tail] is the literal end the sentence must have, so a changed constant is caught too.
     */
    private fun assertDeleteFailed(
        outcome: Outcome,
        refusal: ModelInstaller.Refusal,
        expectedDetail: String,
        tail: String,
    ) {
        assertEquals(refusal, outcome.refused.refusal)
        assertTrue("the refusal must say the file is still on disk", outcome.refused.leftOnDisk)
        assertEquals(expectedDetail, outcome.refused.detail)
        assertTrue("the sentence must end with '$tail': ${outcome.refused.detail}", outcome.refused.detail.endsWith(tail))
        assertFalse(outcome.refused.detail.contains('/') || outcome.refused.detail.contains('\\'))
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertTrue("the sink must note the failed delete: ${outcome.sunk}", outcome.sunk.single().endsWith("; delete failed"))
    }

    /** The same refusal when the delete works: no flag, the plain sentence, no note in the sink. */
    private fun assertDeleteWorked(
        outcome: Outcome,
        refusal: ModelInstaller.Refusal,
        sentence: String,
    ) {
        assertEquals(refusal, outcome.refused.refusal)
        assertFalse("nothing is left on disk, so nothing is reported", outcome.refused.leftOnDisk)
        assertEquals(sentence, outcome.refused.detail)
        assertFalse(outcome.refused.detail.contains(couldNotDelete))
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertFalse("no delete failure to report: ${outcome.sunk}", outcome.sunk.single().contains("delete failed"))
    }

    private val discardedTail = "failed its check but could not be discarded."
    private val saveFailedLeft = ModelMessages.SAVE_FAILED + ModelMessages.COULD_NOT_DELETE

    /** The sentence of a rejected download or model whose delete failed: it must not claim a discard. */
    private fun assertCheckSentenceLeft(outcome: Outcome) {
        assertDeleteFailed(
            outcome,
            ModelInstaller.Refusal.DIGEST_REFUSED,
            ModelMessages.DOWNLOAD_FAILED_CHECK_NOT_DELETED,
            discardedTail,
        )
        assertEquals("The downloaded file failed its check but could not be discarded.", outcome.refused.detail)
        assertFalse("the sentence must not claim a discard: ${outcome.refused.detail}", outcome.refused.detail.contains("was discarded"))
    }

    /** The sentence of a save that failed and left the staged file or the model behind. */
    private fun assertSaveSentenceLeft(outcome: Outcome, refusal: ModelInstaller.Refusal) {
        assertDeleteFailed(outcome, refusal, saveFailedLeft, " $couldNotDelete")
    }

    private fun wrongBytes() = "different model bytes".toByteArray()

    @Test
    fun `install reports a bad download that could not be discarded`() {
        val failed = attempt(Failing.DISCARD, StubFetcher(wrongBytes(), pin))
        assertCheckSentenceLeft(failed)
        assertTrue("the sink must hold the verdict: ${failed.sunk}", failed.sunk.single().contains("PIN_MISMATCH"))
        assertTrue("the rejected download must still be in staging", stagedArchive(failed).isFile)
        assertFalse(failed.store.directoryFor("tiny").exists())

        val worked = attempt(Failing.NONE, StubFetcher(wrongBytes(), pin))
        assertDeleteWorked(worked, ModelInstaller.Refusal.DIGEST_REFUSED, ModelMessages.DOWNLOAD_FAILED_CHECK)
        assertFalse("the rejected download must be gone", stagedArchive(worked).exists())
    }

    @Test
    fun `install reports a staged file it could not discard when the model directory cannot be created`() {
        val blockModelDirectory: (LocalModelStore) -> Unit = { store -> File(store.directoryFor("tiny").path).writeText("blocker") }
        val failed = attempt(Failing.DISCARD, StubFetcher(goodBytes, pin), blockModelDirectory)
        assertSaveSentenceLeft(failed, ModelInstaller.Refusal.STAGING_FAILED)
        assertTrue("the sink must hold the model directory: ${failed.sunk}", failed.sunk.single().contains("cannot create"))
        assertTrue("the staged file must still be in staging", stagedArchive(failed).isFile)

        val worked = attempt(Failing.NONE, StubFetcher(goodBytes, pin), blockModelDirectory)
        assertDeleteWorked(worked, ModelInstaller.Refusal.STAGING_FAILED, ModelMessages.SAVE_FAILED)
        assertFalse("the staged file must be gone", stagedArchive(worked).exists())
    }

    @Test
    fun `install reports a staged file it could not discard when the archive destination is a directory`() {
        val blockDestination: (LocalModelStore) -> Unit = { store -> store.archiveFile("tiny").mkdirs() }
        val failed = attempt(Failing.DISCARD, StubFetcher(goodBytes, pin), blockDestination)
        assertSaveSentenceLeft(failed, ModelInstaller.Refusal.MOVE_FAILED)
        assertTrue("the sink must say the destination is not a file: ${failed.sunk}", failed.sunk.single().contains("destination is not a file"))
        assertTrue("the staged file must still be in staging", stagedArchive(failed).isFile)

        val worked = attempt(Failing.NONE, StubFetcher(goodBytes, pin), blockDestination)
        assertDeleteWorked(worked, ModelInstaller.Refusal.MOVE_FAILED, ModelMessages.SAVE_FAILED)
        assertFalse("the staged file must be gone", stagedArchive(worked).exists())
    }

    @Test
    fun `install reports a staged file it could not discard when the move fails`() {
        // The staged file reports that it cannot be moved; every other call reaches the real file.
        val unmovable: (File) -> File = { real ->
            object : File(real.path) {
                override fun renameTo(dest: File): Boolean = false
            }
        }
        val failed = attempt(Failing.DISCARD, StubFetcher(goodBytes, pin, unmovable))
        assertSaveSentenceLeft(failed, ModelInstaller.Refusal.MOVE_FAILED)
        assertTrue("the sink must say the move failed: ${failed.sunk}", failed.sunk.single().contains("cannot move"))
        assertTrue("the staged file must still be in staging", stagedArchive(failed).isFile)
        assertFalse("nothing may reach the archive path", failed.store.archiveFile("tiny").exists())

        val worked = attempt(Failing.NONE, StubFetcher(goodBytes, pin, unmovable))
        assertDeleteWorked(worked, ModelInstaller.Refusal.MOVE_FAILED, ModelMessages.SAVE_FAILED)
        assertTrue("the sink must say the move failed: ${worked.sunk}", worked.sunk.single().contains("cannot move"))
        assertFalse("the staged file must be gone", stagedArchive(worked).exists())
    }

    @Test
    fun `install reports a model that could not be deleted when the final check refuses`() {
        // The staged bytes pass the staging check; the move then puts different bytes at the archive path.
        val swapsOnMove: (File) -> File = { real ->
            object : File(real.path) {
                override fun renameTo(dest: File): Boolean {
                    dest.writeBytes(wrongBytes())
                    return true
                }
            }
        }
        val failed = attempt(Failing.DELETE, StubFetcher(goodBytes, pin, swapsOnMove))
        assertCheckSentenceLeft(failed)
        assertTrue("the sink must hold the verdict: ${failed.sunk}", failed.sunk.single().contains("PIN_MISMATCH"))
        assertTrue("the rejected model must still be on disk", failed.store.archiveFile("tiny").isFile)

        val worked = attempt(Failing.NONE, StubFetcher(goodBytes, pin, swapsOnMove))
        assertDeleteWorked(worked, ModelInstaller.Refusal.DIGEST_REFUSED, ModelMessages.DOWNLOAD_FAILED_CHECK)
        assertTrue("the sink must hold the verdict: ${worked.sunk}", worked.sunk.single().contains("PIN_MISMATCH"))
        assertFalse("the rejected model must be gone", worked.store.directoryFor("tiny").exists())
    }

    @Test
    fun `install reports a model that could not be deleted when its metadata cannot be recorded`() {
        // A directory where the checksum record must go makes the final metadata write throw.
        val blockChecksumRecord: (LocalModelStore) -> Unit = { store ->
            store.directoryFor("tiny").mkdirs()
            File(store.directoryFor("tiny"), LocalModelStore.CHECKSUMS_FILE).mkdirs()
        }
        val failed = attempt(Failing.DELETE, StubFetcher(goodBytes, pin), blockChecksumRecord)
        assertSaveSentenceLeft(failed, ModelInstaller.Refusal.STAGING_FAILED)
        assertTrue("the sink must hold the metadata failure: ${failed.sunk}", failed.sunk.single().contains("cannot record model metadata"))
        assertTrue("the model must still be on disk", failed.store.archiveFile("tiny").isFile)

        val worked = attempt(Failing.NONE, StubFetcher(goodBytes, pin), blockChecksumRecord)
        assertDeleteWorked(worked, ModelInstaller.Refusal.STAGING_FAILED, ModelMessages.SAVE_FAILED)
        assertTrue("the sink must hold the metadata failure: ${worked.sunk}", worked.sunk.single().contains("cannot record model metadata"))
        assertFalse("the model must be gone", worked.store.directoryFor("tiny").exists())
    }
}
