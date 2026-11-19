package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The keyword stream factory accepts a user model unchanged when its stamp holds, and refuses it when the stamp no longer does. */
class UserKeywordFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = "stt-ondevice user keyword factory"

    /** Records each keywords text it is asked to write and hands back one fixed file. */
    private class UserModelWriter(private val returned: File) : KeywordsFileWriter {
        val texts = mutableListOf<String>()

        override fun write(text: String): File {
            texts.add(text)
            return returned
        }
    }

    private fun factory(writer: UserModelWriter): SherpaOnnxKeywordStreamFactory =
        SherpaOnnxKeywordStreamFactory(writer, 1) { NativeStreamingOpener { _, _ -> error("never opened") } }

    @Test
    fun `a user model with a holding stamp makes a stream and writes the keywords file once`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val writer = UserModelWriter(File(tmp.root, "written-keywords.txt"))
        val model = KeywordModel(KEYWORD_MODEL_ID, dir, Fixtures.VALID_PIN, KeywordSource.USER)
        assertNotNull("$tag a user model with a holding stamp must make a stream", factory(writer).create(model))
        assertEquals("$tag the keywords file is written once", 1, writer.texts.size)
    }

    @Test
    fun `a user model whose stamp no longer holds is refused and writes nothing`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        File(dir, StampedFolderFixtures.DECODER).writeText("xx")
        val writer = UserModelWriter(File(tmp.root, "written-keywords.txt"))
        val model = KeywordModel(KEYWORD_MODEL_ID, dir, Fixtures.VALID_PIN, KeywordSource.USER)
        try {
            factory(writer).create(model)
            fail("$tag a user model whose stamp no longer holds must be refused")
        } catch (expected: SherpaTranscriptionException) {
            // the refusal is the expected outcome
        }
        assertEquals("$tag nothing is written for the refused model", 0, writer.texts.size)
    }
}
