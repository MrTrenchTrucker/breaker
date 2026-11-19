package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The user model, the chooser and the real keyword factory together. A user folder that matches its stamp reaches
 * the factory; a user folder whose modified time changed is ignored by the chooser, so the default goes to the
 * factory and the user folder never does.
 */
class UserKeywordSeamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = "stt-ondevice user keyword seam"

    /** Counts the keywords files the factory asks for and hands back one fixed file. */
    private class SeamWriter(private val returned: File) : KeywordsFileWriter {
        var writes: Int = 0
            private set

        override fun write(text: String): File {
            writes += 1
            return returned
        }
    }

    private fun factory(writer: SeamWriter): SherpaOnnxKeywordStreamFactory =
        SherpaOnnxKeywordStreamFactory(writer, 1) { NativeStreamingOpener { _, _ -> error("never opened") } }

    /** A chooser over [store] whose default is the stamped folder [defaultDir]; [defaultCalls] counts the default asks. */
    private fun chooserOver(
        store: UserKeywordStore,
        defaultDir: File,
        defaultCalls: IntArray,
        phrases: List<KeywordLine> = FixedPhrases.all(),
    ): KeywordChooser {
        val loader = UserKeywordLoader(store, phrases)
        return KeywordChooser(
            { loader.load() },
            {
                defaultCalls[0]++
                KeywordLoad.Ready(KeywordModel(KEYWORD_MODEL_ID, defaultDir, Fixtures.VALID_PIN))
            },
        )
    }

    private fun changeModifiedTime(dir: File) {
        val decoder = File(dir, StampedFolderFixtures.DECODER)
        assertTrue("$tag fixture: the modified time moves", decoder.setLastModified(decoder.lastModified() - 60_000L))
    }

    @Test
    fun `a stamped user model that matches is chosen and the real factory makes a stream for it`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        val calls = IntArray(1)
        val choice = chooserOver(store, tmp.newFolder(), calls).choose()
        val chosen = checkNotNull((choice.load as? KeywordLoad.Ready)?.model) { "$tag the chooser must answer a ready model" }
        assertEquals("$tag the matching user model is chosen", KeywordSource.USER, chosen.source)
        assertEquals("$tag no reason is given for the chosen user model", null, choice.userIgnored)
        assertEquals("$tag the default is not asked", 0, calls[0])
        val writer = SeamWriter(File(tmp.root, "written-keywords.txt"))
        assertNotNull("$tag the real factory makes a stream for the chosen user model", factory(writer).create(chosen))
        assertEquals("$tag the keywords file is written once", 1, writer.writes)
    }

    @Test
    fun `a stamped user model whose modified time changed is ignored and the default goes to the factory`() {
        val store = UserKeywordStore(tmp.root)
        changeModifiedTime(StampedFolderFixtures.stampedDir(store.directory()))
        val defaultDir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val calls = IntArray(1)
        val choice = chooserOver(store, defaultDir, calls).choose()
        assertEquals("$tag the user model is ignored with the stamp reason", UserKeywordReason.STAMP_MISMATCH, choice.userIgnored)
        assertEquals("$tag the default is asked once", 1, calls[0])
        val chosen = checkNotNull((choice.load as? KeywordLoad.Ready)?.model) { "$tag the chooser must answer a ready default" }
        assertEquals("$tag the chosen model is the default", KeywordSource.DEFAULT, chosen.source)
        assertEquals("$tag the chosen folder is the default folder", defaultDir, chosen.directory)
        val writer = SeamWriter(File(tmp.root, "written-keywords.txt"))
        assertNotNull("$tag the real factory makes a stream for the default", factory(writer).create(chosen))
        assertEquals("$tag the keywords file is written once", 1, writer.writes)
    }

    @Test
    fun `the real factory refuses the modified time changed user folder so the chooser must not hand it over`() {
        val store = UserKeywordStore(tmp.root)
        changeModifiedTime(StampedFolderFixtures.stampedDir(store.directory()))
        val writer = SeamWriter(File(tmp.root, "written-keywords.txt"))
        val userModel = KeywordModel(KEYWORD_MODEL_ID, store.directory(), Fixtures.VALID_PIN, KeywordSource.USER)
        try {
            factory(writer).create(userModel)
            fail("$tag the factory must refuse a folder whose modified time changed")
        } catch (expected: SherpaTranscriptionException) {
            // the refusal is the reason the chooser must ignore the user model
        }
        assertEquals("$tag nothing is written for the refused folder", 0, writer.writes)
    }

    /**
     * The one counted path in these tests to the real factory's create: it takes the chooser's own answer, records
     * the source of the ready model in that answer, and creates the stream from that model. A refusal check that
     * calls the factory directly is not counted here.
     */
    private fun createFromChoice(choice: KeywordChoice, handed: MutableList<KeywordSource>, writer: SeamWriter): FedWordStream {
        val answered = checkNotNull((choice.load as? KeywordLoad.Ready)?.model) { "$tag the chooser must answer a ready model" }
        handed.add(answered.source)
        return factory(writer).create(answered)
    }

    @Test
    fun `a user folder whose tokens lack a phrase token is ignored and the chooser answer routed to the factory is the default`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory(), StampedFolderFixtures.tokenTextWithout(FixedPhrases.SEND.tokens.first()))
        val defaultDir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val calls = IntArray(1)
        val choice = chooserOver(store, defaultDir, calls).choose()
        assertEquals("$tag the user model is ignored as tokens missing", UserKeywordReason.TOKENS_MISSING, choice.userIgnored)
        assertEquals("$tag the default is asked once", 1, calls[0])
        val chosen = checkNotNull((choice.load as? KeywordLoad.Ready)?.model) { "$tag the chooser must answer a ready default" }
        assertEquals("$tag the chosen load is the default", KeywordSource.DEFAULT, chosen.source)
        val writer = SeamWriter(File(tmp.root, "written-keywords.txt"))
        val userModel = KeywordModel(KEYWORD_MODEL_ID, store.directory(), Fixtures.VALID_PIN, KeywordSource.USER)
        try {
            factory(writer).create(userModel)
            fail("$tag the factory must refuse a folder whose tokens lack a phrase token")
        } catch (expected: SherpaTranscriptionException) {
            // the refusal is the reason the chooser must ignore the user model
        }
        val handed = mutableListOf<KeywordSource>()
        assertNotNull("$tag the real factory makes a stream for the default", createFromChoice(choice, handed, writer))
        assertEquals("$tag the counted factory calls take the default only", listOf(KeywordSource.DEFAULT), handed)
    }

    @Test
    fun `a user model the phrase list outruns is ignored and the chooser answer routed to the factory is the default`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        val extra = FixedPhrases.all() + KeywordLine(listOf("EXTRA"))
        val defaultDir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val calls = IntArray(1)
        val choice = chooserOver(store, defaultDir, calls, extra).choose()
        assertEquals("$tag the user model is ignored as tokens missing", UserKeywordReason.TOKENS_MISSING, choice.userIgnored)
        assertEquals("$tag the default is asked once", 1, calls[0])
        val chosen = checkNotNull((choice.load as? KeywordLoad.Ready)?.model) { "$tag the chooser must answer a ready default" }
        assertEquals("$tag the chosen load is the default", KeywordSource.DEFAULT, chosen.source)
        val handed = mutableListOf<KeywordSource>()
        assertNotNull("$tag the real factory makes a stream for the default", createFromChoice(choice, handed, SeamWriter(File(tmp.root, "written-keywords.txt"))))
        assertEquals("$tag the counted factory calls take the default only", listOf(KeywordSource.DEFAULT), handed)
    }
}
