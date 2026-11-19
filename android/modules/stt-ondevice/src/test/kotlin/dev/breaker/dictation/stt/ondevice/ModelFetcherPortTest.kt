package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The additive four-argument [ModelFetcher.fetchModel]: its default body calls the three-argument
 * form, so a fetcher that implements only that form (or only the abstract two-argument one) keeps
 * working and none of the existing implementers has to change.
 */
class ModelFetcherPortTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Implements the three-argument form and counts how often it was reached. */
    private class ThreeArgFetcher : ModelFetcher {
        var threeArgCalls = 0
            private set

        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result =
            fetchModel(entry, stagingDir) { _, _ -> }

        override fun fetchModel(
            entry: ModelEntry,
            stagingDir: File,
            onProgress: (received: Long, total: Long) -> Unit,
        ): ModelFetcher.Result {
            threeArgCalls++
            val file = File(stagingDir, entry.id + ".archive")
            file.writeBytes(byteArrayOf(1, 2, 3))
            onProgress(3L, 3L)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    /** Knows only the abstract two-argument form, like the neighbour fakes. */
    private class TwoArgFetcher : ModelFetcher {
        var twoArgCalls = 0
            private set

        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            twoArgCalls++
            val file = File(stagingDir, entry.id + ".archive")
            file.writeBytes(byteArrayOf(9))
            return ModelFetcher.Result.Fetched(file)
        }
    }

    private fun staging(): File = tmp.newFolder("staging-" + System.nanoTime())

    @Test
    fun `the four-argument default body reaches a three-argument implementer`() {
        val fetcher = ThreeArgFetcher()
        val dir = staging()
        val result = fetcher.fetchModel(testEntry(), dir, { _, _ -> }, { false })
        assertEquals("the three-argument form was called once", 1, fetcher.threeArgCalls)
        assertEquals("its file came back", "tiny.archive", (result as ModelFetcher.Result.Fetched).file.name)
    }

    @Test
    fun `the four-argument default body falls through to a two-argument implementer`() {
        val fetcher = TwoArgFetcher()
        val dir = staging()
        val result = fetcher.fetchModel(testEntry(), dir, { _, _ -> }, { true })
        assertEquals("the two-argument form was called once", 1, fetcher.twoArgCalls)
        assertEquals("its file came back", "tiny.archive", (result as ModelFetcher.Result.Fetched).file.name)
    }

    @Test
    fun `the four-argument form's cancel flag is never dereferenced by the default body`() {
        val fetcher = ThreeArgFetcher()
        val dir = staging()
        // A flag that would throw if the default body polled it: the default delegates without
        // looking at the flag, so the call succeeds.
        val result = fetcher.fetchModel(testEntry(), dir, { _, _ -> }, { throw AssertionError("polled") })
        assertTrue("the default body did not poll the flag", result is ModelFetcher.Result.Fetched)
    }
}
