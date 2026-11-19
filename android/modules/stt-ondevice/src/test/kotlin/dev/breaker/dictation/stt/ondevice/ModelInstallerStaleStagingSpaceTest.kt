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
 * The free-space check counts the stale partial downloads already sitting in staging as
 * reclaimable and sweeps them before the fetch, so they cannot turn an install that has
 * room into a false NO_SPACE. The fetcher records every request, so "no request" is read
 * from the recorded list, not inferred.
 */
class ModelInstallerStaleStagingSpaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mib = 1_048_576L
    private val archiveBytes = 1_000L

    /** tiny's unpack profile lets the unpack write 80 MiB. */
    private val unpackBytes = 80L * mib
    private val needBytes = archiveBytes + unpackBytes
    private val staleBytes = 4_000_000L

    /** Records every model fetch; answers each with a failure so a run past the check stops there. */
    private class RecordingFetcher : ModelFetcher {
        val requestedIds = ArrayList<String>()

        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            requestedIds.add(entry.id)
            return ModelFetcher.Result.Failed("scripted")
        }
    }

    private fun entry() = ModelEntry(
        "tiny",
        ModelFamily.SHERPA_ONNX,
        "https://example.com/model",
        "0".repeat(64),
        1,
        "Apache-2.0",
        false,
        sizeBytes = archiveBytes,
    )

    private fun storeWithStaleDownload(): Pair<LocalModelStore, File> {
        val store = LocalModelStore(tmp.newFolder())
        store.stagingDirectory().mkdirs()
        val stale = File(store.stagingDirectory(), "other-model.download")
        Fixtures.createFileOfSize(stale, staleBytes)
        return store to stale
    }

    @Test
    fun `a stale partial download does not change a should-pass verdict`() {
        val (store, stale) = storeWithStaleDownload()
        assertTrue("fixture: the stale download is on disk", stale.isFile)
        val fetcher = RecordingFetcher()
        // Exactly one byte less room than the need, made up by the stale file the sweep reclaims.
        val result = ModelInstaller(store, fetcher, usableSpace = { needBytes - staleBytes }).install(entry())
        assertTrue("expected a refusal but got $result", result is ModelInstaller.InstallResult.Refused)
        assertEquals(
            "the check passed, the scripted fetch failure is what refused",
            ModelInstaller.Refusal.FETCH_FAILED,
            (result as ModelInstaller.InstallResult.Refused).refusal,
        )
        assertEquals("the model was fetched once", listOf("tiny"), fetcher.requestedIds)
        assertFalse("the stale download was swept before the fetch", stale.exists())
    }

    @Test
    fun `stale bytes cannot rescue a shortfall larger than they are`() {
        val (store, stale) = storeWithStaleDownload()
        assertTrue("fixture: the stale download is on disk", stale.isFile)
        val fetcher = RecordingFetcher()
        val result = ModelInstaller(store, fetcher, usableSpace = { needBytes - staleBytes - 1L }).install(entry())
        assertTrue("expected a refusal but got $result", result is ModelInstaller.InstallResult.Refused)
        assertEquals(ModelInstaller.Refusal.NO_SPACE, (result as ModelInstaller.InstallResult.Refused).refusal)
        assertEquals("no request may be made once the space check refuses", emptyList<String>(), fetcher.requestedIds)
    }

    @Test
    fun `a stale partial whose delete fails refuses NO_SPACE before any fetch`() {
        val (store, stale) = storeWithStaleDownload()
        assertTrue("fixture: the stale download is on disk", stale.isFile)
        val fetcher = RecordingFetcher()
        // Free alone is one stale-file short of the need; the sweep cannot reclaim it.
        val result = ModelInstaller(
            store,
            fetcher,
            usableSpace = { needBytes - staleBytes },
            sweepDelete = { false },
        ).install(entry())
        assertTrue("expected a refusal but got $result", result is ModelInstaller.InstallResult.Refused)
        assertEquals(
            "the bytes that could not be freed must refuse before the fetch",
            ModelInstaller.Refusal.NO_SPACE,
            (result as ModelInstaller.InstallResult.Refused).refusal,
        )
        assertEquals("no request may be made once the space check refuses", emptyList<String>(), fetcher.requestedIds)
        assertTrue("the stale download is left on disk", stale.exists())
    }
}
