package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The token check of a user model over real files: the factory's own cap, reading and refusal rules. */
class UserKeywordTokensTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = "stt-ondevice user keyword tokens"
    private val cap = SherpaOnnxKeywordStreamFactory.MAX_TOKENS_BYTES

    private fun fileWith(text: String): File = File(tmp.newFolder(), "tokens.txt").apply { writeText(text) }

    @Test
    fun `a tokens file holding every phrase token fits`() {
        assertTrue("$tag a compatible file must fit", UserKeywordTokens.fits(fileWith(StampedFolderFixtures.tokenText())))
    }

    @Test
    fun `a tokens file lacking one phrase token does not fit`() {
        val lacking = FixedPhrases.SEND.tokens.first()
        val file = fileWith(StampedFolderFixtures.tokenTextWithout(lacking))
        assertFalse("$tag a file without one phrase token must not fit", UserKeywordTokens.fits(file))
    }

    @Test
    fun `a tokens file over the cap does not fit`() {
        assertFalse("$tag one byte over the cap must not fit", UserKeywordTokens.fits(fileWith("x".repeat((cap + 1).toInt()))))
    }

    @Test
    fun `a tokens file exactly at the cap is read and fits`() {
        val base = StampedFolderFixtures.tokenText()
        val pad = (cap - base.toByteArray(Charsets.UTF_8).size).toInt()
        val file = fileWith(base + "p" + "x".repeat(pad - 4) + " 9\n")
        assertEquals("$tag fixture: the file is exactly the cap", cap, file.length())
        assertTrue("$tag a file of exactly the cap must fit", UserKeywordTokens.fits(file))
    }

    @Test
    fun `a missing tokens file does not fit`() {
        assertFalse("$tag a missing file must not fit", UserKeywordTokens.fits(File(tmp.root, "missing.txt")))
    }

    @Test
    fun `a phrase list the renderer refuses fits no file`() {
        val file = fileWith(StampedFolderFixtures.tokenText())
        assertFalse("$tag a phrase list the renderer refuses must not fit", UserKeywordTokens.fits(file, listOf(KeywordLine(emptyList()))))
    }

    /** The real factory over the internal seam: its writer and opener are never reached by these cases. */
    private fun factoryOver(): SherpaOnnxKeywordStreamFactory =
        SherpaOnnxKeywordStreamFactory(
            KeywordsFileWriter { text -> File(tmp.newFolder(), "keywords.txt").apply { writeText(text) } },
            1,
        ) { NativeStreamingOpener { _, _ -> error("never opened") } }

    @Test
    fun `the factory refuses and fits rejects the same token file that lacks a phrase token`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder(), StampedFolderFixtures.tokenTextWithout(FixedPhrases.SEND.tokens.first()))
        assertFalse("$tag fits rejects the file", UserKeywordTokens.fits(File(dir, StampedFolderFixtures.TOKENS)))
        try {
            factoryOver().create(KeywordModel(KEYWORD_MODEL_ID, dir, Fixtures.VALID_PIN))
            fail("$tag the factory must refuse the file that fits rejects")
        } catch (refused: SherpaTranscriptionException) {
            // refused, as fits says
        }
    }

    @Test
    fun `the factory accepts and fits accepts the same compatible token file`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        assertTrue("$tag fits accepts the file", UserKeywordTokens.fits(File(dir, StampedFolderFixtures.TOKENS)))
        assertNotNull("$tag the factory makes a stream for the file that fits accepts", factoryOver().create(KeywordModel(KEYWORD_MODEL_ID, dir, Fixtures.VALID_PIN)))
    }

    @Test
    fun `a phrase list with a token the file lacks does not fit`() {
        val file = fileWith(StampedFolderFixtures.tokenText())
        val extra = FixedPhrases.all() + KeywordLine(listOf("EXTRA"))
        assertFalse("$tag a phrase list asking for a missing token must not fit", UserKeywordTokens.fits(file, extra))
    }
}
