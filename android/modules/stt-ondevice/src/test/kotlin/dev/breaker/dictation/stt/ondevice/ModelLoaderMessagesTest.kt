package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the loader tells the user (a fixed sentence per refusal), what it keeps
 * for the debug sink (the technical text), and what it does with an archive
 * whose checksums could not be read (keep it, create nothing, check again).
 */
class ModelLoaderMessagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"
    private val bytes = "model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val garbage = "not valid checksums"
    private val engineFailureText = "init failed at /data/app/lib/libsherpa.so with IllegalStateException"

    private class RecordingSink : ModelDebugSink {
        val messages = ArrayList<String>()
        override fun debug(message: String) {
            messages.add(message)
        }
    }

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript =
            SherpaTranscript("", emptyList<SttSegment>(), "en")

        override fun release() {}
    }

    /** Counts `create` calls; throws [failure] from each call when one is given. */
    private class CountingFactory(private val failure: Exception? = null) : SherpaRecognizerFactory {
        var creates = 0
        override fun create(model: SherpaModel): SherpaRecognizer {
            creates++
            if (failure != null) throw failure
            return IdleRecognizer
        }
    }

    private class Scenario(
        val id: String,
        val store: LocalModelStore,
        val factory: CountingFactory,
        val sink: RecordingSink,
        entry: ModelEntry?,
    ) {
        val loader = ModelLoader(store, factory, { requested -> if (requested == id) entry else null }, sink)
        val archive: File get() = File(store.directoryFor(id), LocalModelStore.ARCHIVE_NAME)

        fun refused(): ModelLoader.LoadResult.Refused {
            val result = loadOrFail(loader, id)
            assertTrue("expected a refusal but got $result", result is ModelLoader.LoadResult.Refused)
            return result as ModelLoader.LoadResult.Refused
        }
    }

    private fun entryFor(family: ModelFamily = ModelFamily.SHERPA_ONNX) =
        ModelEntry(modelId, family, "https://example.com/model", digest, 1, "Apache-2.0", false)

    private fun scenario(
        entry: ModelEntry?,
        factory: CountingFactory = CountingFactory(),
        sink: RecordingSink = RecordingSink(),
    ) = Scenario(modelId, LocalModelStore(tmp.newFolder()), factory, sink, entry)

    private fun validChecksums() = "model.archive\t$digest"

    /** Installs [archiveBytes] with the verified marker, and stores [checksums] when given. */
    private fun install(s: Scenario, archiveBytes: ByteArray = bytes, checksums: String? = null): Scenario {
        Fixtures.writeBytes(s.archive, archiveBytes)
        s.store.markVerified(s.id, digest)
        if (checksums != null) s.store.storeChecksums(s.id, checksums)
        assertTrue("fixture: the model must be installed", s.store.isInstalled(s.id))
        return s
    }

    private fun unknownModel() = scenario(null)
    private fun wrongFamily() = scenario(entryFor(ModelFamily.WHISPER))
    private fun notInstalled() = scenario(entryFor())
    private fun missingChecksums() = install(scenario(entryFor()))
    private fun garbageChecksums() = install(scenario(entryFor()), checksums = garbage)
    private fun failedCheck() = install(scenario(entryFor()), "tampered".toByteArray(), validChecksums())
    private fun engineFails() = install(
        scenario(entryFor(), CountingFactory(IllegalStateException(engineFailureText))),
        checksums = validChecksums(),
    )

    @Test
    fun `load keeps the archive when the stored checksums are missing`() {
        val s = missingChecksums()
        val refused = s.refused()
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, refused.refusal)
        assertEquals(ModelMessages.NOT_VERIFIED_YET, refused.detail)
        assertTrue("the archive must stay", s.archive.isFile)
        assertArrayEquals(bytes, s.archive.readBytes())
        assertEquals(digest, s.store.lastVerifiedDigest(s.id))
        assertNull(s.store.storedChecksums(s.id))
        assertEquals(0, s.factory.creates)
    }

    @Test
    fun `load keeps the archive when the stored checksums cannot be parsed`() {
        val s = garbageChecksums()
        val refused = s.refused()
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, refused.refusal)
        assertEquals(ModelMessages.NOT_VERIFIED_YET, refused.detail)
        assertTrue("the archive must stay", s.archive.isFile)
        assertArrayEquals(bytes, s.archive.readBytes())
        assertEquals(digest, s.store.lastVerifiedDigest(s.id))
        assertEquals(garbage, s.store.storedChecksums(s.id))
        assertEquals(0, s.factory.creates)
    }

    @Test
    fun `load never creates a recognizer for a model that was not verified`() {
        val cases = listOf(
            missingChecksums() to ModelLoader.Refusal.CHECKSUMS_UNREADABLE,
            garbageChecksums() to ModelLoader.Refusal.CHECKSUMS_UNREADABLE,
            failedCheck() to ModelLoader.Refusal.VERIFICATION_REFUSED,
        )
        assertEquals(3, cases.size)
        var checked = 0
        for ((s, expected) in cases) {
            assertEquals(expected, s.refused().refusal)
            assertEquals("a recognizer was created for a model that was not verified", 0, s.factory.creates)
            checked++
        }
        assertEquals(3, checked)
    }

    @Test
    fun `load verifies again on the next call once the checksums are readable`() {
        val s = missingChecksums()
        assertEquals(ModelMessages.NOT_VERIFIED_YET, s.refused().detail)
        assertEquals(0, s.factory.creates)

        s.store.storeChecksums(s.id, validChecksums())
        val second = loadOrFail(s.loader, s.id)

        assertTrue("second load: $second", second.isReady)
        assertEquals(1, s.factory.creates)
    }

    @Test
    fun `load deletes a model that fails its check and says so in plain words`() {
        val s = failedCheck()
        assertTrue("fixture: the archive exists before the load", s.archive.isFile)
        val refused = s.refused()
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, refused.refusal)
        assertEquals(ModelMessages.CHECK_FAILED_DELETED, refused.detail)
        assertFalse("the failed archive must be deleted", s.store.directoryFor(s.id).exists())
        assertEquals(0, s.factory.creates)
        assertEquals(1, s.sink.messages.size)
        assertTrue(s.sink.messages.single(), s.sink.messages.single().contains("PIN_MISMATCH"))
    }

    @Test
    fun `load gives the unknown model sentence and sends the id to the debug sink`() {
        val s = unknownModel()
        val refused = s.refused()
        assertEquals(ModelLoader.Refusal.UNKNOWN_MODEL, refused.refusal)
        assertEquals(ModelMessages.MODEL_UNKNOWN, refused.detail)
        assertEquals(1, s.sink.messages.size)
        assertTrue(s.sink.messages.single(), s.sink.messages.single().contains("'$modelId'"))
    }

    @Test
    fun `load gives the wrong family sentence and keeps the family`() {
        val s = wrongFamily()
        val refused = s.refused()
        assertEquals(ModelLoader.Refusal.WRONG_FAMILY, refused.refusal)
        assertEquals(ModelMessages.MODEL_WRONG_FAMILY, refused.detail)
        assertEquals(ModelFamily.WHISPER, refused.family)
        assertEquals(1, s.sink.messages.size)
        assertTrue(s.sink.messages.single(), s.sink.messages.single().contains(ModelFamily.WHISPER.name))
    }

    @Test
    fun `load gives the not installed sentence and sends the id to the debug sink`() {
        val s = notInstalled()
        val refused = s.refused()
        assertEquals(ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals(ModelMessages.MODEL_NOT_INSTALLED, refused.detail)
        assertEquals(1, s.sink.messages.size)
        assertTrue(s.sink.messages.single(), s.sink.messages.single().contains("'$modelId'"))
    }

    @Test
    fun `load gives the engine sentence and sends the engine text to the debug sink`() {
        val s = engineFails()
        val refused = s.refused()
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
        assertEquals(ModelMessages.ENGINE_COULD_NOT_START, refused.detail)
        assertEquals(1, s.factory.creates)
        assertTrue("the archive must stay", s.archive.isFile)
        assertEquals(1, s.sink.messages.size)
        assertTrue(s.sink.messages.single(), s.sink.messages.single().contains(engineFailureText))
    }

    @Test
    fun `no refusal detail of the loader holds a path separator or an exception name`() {
        val engine = engineFails()
        val refusals = listOf(
            unknownModel(),
            wrongFamily(),
            notInstalled(),
            missingChecksums(),
            garbageChecksums(),
            failedCheck(),
            engine,
        ).map { it.refused() }

        assertEquals(7, refusals.size)
        assertEquals(ModelLoader.Refusal.values().toSet(), refusals.map { it.refusal }.toSet())
        // Control: the words scanned for do occur in the technical text, so a leak would show.
        val technical = engine.sink.messages.single()
        assertTrue(technical, technical.contains("/") && technical.contains("Exception"))

        var scanned = 0
        for (refused in refusals) {
            for (forbidden in listOf("/", "\\", "Exception", "Error")) {
                assertFalse(
                    "${refused.refusal} detail '${refused.detail}' holds '$forbidden'",
                    refused.detail.contains(forbidden),
                )
            }
            scanned++
        }
        assertEquals(7, scanned)
    }

    @Test
    fun `the default sink drops the technical text and the loader still refuses`() {
        val store = LocalModelStore(tmp.newFolder())
        Fixtures.writeBytes(File(store.directoryFor(modelId), LocalModelStore.ARCHIVE_NAME), bytes)
        store.markVerified(modelId, digest)
        store.storeChecksums(modelId, validChecksums())
        val loader = ModelLoader(
            store,
            CountingFactory(IllegalStateException(engineFailureText)),
            { requested -> if (requested == modelId) entryFor() else null },
        )

        val engine = loadOrFail(loader, modelId) as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, engine.refusal)
        assertEquals(ModelMessages.ENGINE_COULD_NOT_START, engine.detail)

        val unknown = loadOrFail(loader, "other") as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.UNKNOWN_MODEL, unknown.refusal)
        assertEquals(ModelMessages.MODEL_UNKNOWN, unknown.detail)
    }
}

/** Loads [id] and fails with a clear message if the load throws instead of returning. */
private fun loadOrFail(loader: ModelLoader, id: String): ModelLoader.LoadResult =
    try {
        loader.load(id)
    } catch (t: Throwable) {
        throw AssertionError("load let ${t.javaClass.name} escape instead of returning a result: $t", t)
    }
