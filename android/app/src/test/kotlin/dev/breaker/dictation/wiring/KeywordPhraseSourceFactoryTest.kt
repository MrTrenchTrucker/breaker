package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.LocalModelStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's keyword source built by keywordPhraseSource over a real model store and a real files folder, both empty.
 * No keyword model is installed here, and nothing in the app test source set can install a verified one: the
 * loader inside keywordPhraseSource looks the model up in the real registry, so an installed model needs the real
 * archive. A ready load would make the real factory write the keywords file and build the stream, and the real
 * engine would open when that stream is started. These tests cover only the empty case, so no keywords file is
 * written and no engine opens here.
 */
class KeywordPhraseSourceFactoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun filesDir(): File = tmp.newFolder("files")

    private fun emptyStore(): LocalModelStore = LocalModelStore(File(tmp.root, "models"))

    @Test
    fun `with no keyword model installed the source answers null for every take`() {
        val source: PhraseStreamSource = keywordPhraseSource(emptyStore(), filesDir())
        assertNull("app: the source answered a stream with no keyword model installed", source.newStream())
        assertNull("app: the second take got a stream with no keyword model installed", source.newStream())
    }

    @Test
    fun `building the source and asking it writes no keywords file and makes no folder`() {
        val files: File = filesDir()
        val source: PhraseStreamSource = keywordPhraseSource(emptyStore(), files)
        assertEquals("app: building the source touched the files folder", emptyList<String>(), (files.list() ?: emptyArray()).sorted())
        source.newStream()
        assertEquals("app: an empty take touched the files folder", emptyList<String>(), (files.list() ?: emptyArray()).sorted())
        assertFalse("app: the keywords folder was made without a model", File(files, KEYWORDS_FOLDER).exists())
    }

    @Test
    fun `the keywords folder keeps its name`() {
        assertEquals("app: the keywords folder name changed", "keywords", KEYWORDS_FOLDER)
    }

    @Test
    fun `a handler over the app source starts nothing and gives no stream when no model is installed`() {
        val handler: PhraseSendHandler = phraseHandlerFor(ManualBackground(), ManualMain(), keywordPhraseSource(emptyStore(), filesDir()))
        handler.begun { }
        assertNull("app: the audio tee got a stream with no keyword model installed", handler.currentStream())
    }
}
