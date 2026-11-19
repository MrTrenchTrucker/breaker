package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The source of a keyword model: the default when none is given, the default loader's own model, and the user source kept. */
class KeywordModelSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = "stt-ondevice keyword source"
    private val bytes = "keyword model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val entry = ModelEntry(KEYWORD_MODEL_ID, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())

    @Test
    fun `a keyword model built without a source has the default source`() {
        assertEquals("$tag the default source", KeywordSource.DEFAULT, KeywordModel(KEYWORD_MODEL_ID, File("model-directory"), digest).source)
    }

    @Test
    fun `a keyword model built with the user source keeps that source`() {
        assertEquals("$tag the user source", KeywordSource.USER, KeywordModel(KEYWORD_MODEL_ID, File("model-directory"), digest, KeywordSource.USER).source)
    }

    @Test
    fun `the default loader gives a ready model with the default source`() {
        val store = LocalModelStore(tmp.root)
        val dir = store.directoryFor(KEYWORD_MODEL_ID)
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(bytes)
        store.markVerified(KEYWORD_MODEL_ID, digest)
        Fixtures.seedExtracted(store, KEYWORD_MODEL_ID)
        val load = KeywordModelLoader(store, { id -> if (id == KEYWORD_MODEL_ID) entry else null }, NoDebugSink).load()
        assertTrue("$tag expected Ready but got $load", load is KeywordLoad.Ready)
        assertEquals("$tag the default loader gives the default source", KeywordSource.DEFAULT, (load as KeywordLoad.Ready).model.source)
    }
}
