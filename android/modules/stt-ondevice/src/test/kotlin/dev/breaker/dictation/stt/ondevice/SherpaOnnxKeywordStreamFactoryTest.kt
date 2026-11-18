package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

/** Records each write and hands back a fixed file, or fails as told. */
private class CountingWriter(val returned: File, private val failure: IOException? = null) : KeywordsFileWriter {
    val texts = mutableListOf<String>()

    override fun write(text: String): File {
        texts.add(text)
        failure?.let { throw it }
        return returned
    }
}

/** Records the keywords file each opener is asked for, and what the engine open was given. */
private class OpenerRecorder(val fake: FakeSpotterStream) {
    val keywordFiles = mutableListOf<File>()
    val openedFiles = mutableListOf<TransducerFiles>()
    val openedThreads = mutableListOf<Int>()

    fun openerFor(keywordsFile: File): NativeStreamingOpener {
        keywordFiles.add(keywordsFile)
        return NativeStreamingOpener { files, threads ->
            openedFiles.add(files)
            openedThreads.add(threads)
            object : NativeStreamingRecognizer {
                override fun createStream(): NativeStream = HitNativeStream(fake)

                override fun release() {}
            }
        }
    }
}

/**
 * The keyword stream factory over fakes: every refusal happens before the writer is called and before any
 * opener is asked for, and a good model writes once and opens once. Nothing here loads the native library.
 * The real binding is not run by any unit test: it is only compiled here and its behaviour on a device is
 * not measured.
 */
class SherpaOnnxKeywordStreamFactoryTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = "stt-ondevice keyword factory:"
    private val mark = "$WORD_MARKER"
    private val encoder = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private val decoder = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
    private val joiner = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private val tokens = "tokens.txt"
    private val names = listOf(encoder, decoder, joiner, tokens)
    private val phraseTokens: Set<String> = FixedPhrases.all().flatMap { it.tokens }.toSet()

    private val profileMessage = "no keyword profile for this model"
    private val filesMessage = "the keyword model files are missing or empty"
    private val tokensMessage = "the keyword list does not fit the keyword model"
    private val writeMessage = "the keyword list could not be written"

    /** A token table with every token of the fixed phrases, except [without], padded by one line of [extraBytes] bytes. */
    private fun tokenText(without: String? = null, extraBytes: Int = 0): String {
        val all = listOf("<blk>") + phraseTokens.filter { it != without }
        val base = all.mapIndexed { i, t -> "$t $i" }.joinToString("\n", postfix = "\n")
        if (extraBytes <= 0) return base
        return base + "p" + "x".repeat(extraBytes - 4) + " 9\n"
    }

    private fun modelDir(text: String = tokenText(), stamped: Boolean = true, skip: String? = null): File {
        val dir = tmp.newFolder()
        for (name in names) {
            if (name == skip) continue
            File(dir, name).writeText(if (name == tokens) text else "x")
        }
        if (stamped) {
            val stamp = checkNotNull(UnpackedStamp.measure(dir, names, Fixtures.VALID_PIN) { ModelIntegrity.sha256(it) })
            File(dir, UnpackedStamp.FILE_NAME).writeText(stamp.encode())
        }
        return dir
    }

    private fun keywordModel(dir: File, id: String = KEYWORD_MODEL_ID) = KeywordModel(id, dir, "digest")

    private class Setup(val writer: CountingWriter, val recorder: OpenerRecorder, val factory: SherpaOnnxKeywordStreamFactory)

    private fun setup(failure: IOException? = null, threads: Int = 2, script: List<SpotterHit?> = listOf(null)): Setup {
        val writer = CountingWriter(File(tmp.root, "written-keywords.txt"), failure)
        val recorder = OpenerRecorder(FakeSpotterStream(script))
        return Setup(writer, recorder, SherpaOnnxKeywordStreamFactory(writer, threads) { recorder.openerFor(it) })
    }

    private fun refusal(label: String, block: () -> Unit): SherpaTranscriptionException {
        try {
            block()
        } catch (e: SherpaTranscriptionException) {
            return e
        }
        fail("$tag $label: expected a refusal but nothing was refused")
        throw IllegalStateException("unreachable")
    }

    /** The refusal must carry the fixed message and leak no path, file name or model id, and nothing may be written or opened. */
    private fun assertRefused(label: String, s: Setup, dir: File, expected: String, block: () -> Unit) {
        val refused = refusal(label, block)
        assertEquals("$tag $label: the fixed message", expected, refused.message)
        assertNull("$tag $label: a refusal carries no cause that could name a path", refused.cause)
        val text = checkNotNull(refused.message)
        assertFalse("$tag $label: the message names the directory", text.contains(dir.path))
        assertFalse("$tag $label: the message names a model file", text.contains(".onnx") || text.contains("tokens.txt"))
        assertFalse("$tag $label: the message names the model id", text.contains(KEYWORD_MODEL_ID))
        assertEquals("$tag $label: the writer must not be called", 0, s.writer.texts.size)
        assertEquals("$tag $label: no opener may be asked for", 0, s.recorder.keywordFiles.size)
    }

    @Test
    fun `a model id with no keyword profile is refused and nothing is written or opened`() {
        val s = setup()
        val dir = modelDir()
        for (id in listOf("unknown", "", "small", "tiny", "base", "KWS-GIGASPEECH-3M", "kws-gigaspeech-3m ")) {
            assertRefused("id [$id]", s, dir, profileMessage) { s.factory.create(keywordModel(dir, id)) }
        }
    }

    @Test
    fun `a directory missing a file or holding an empty one is refused and nothing is written or opened`() {
        val s = setup()
        for (victim in names) {
            val missing = modelDir(stamped = false, skip = victim)
            assertRefused("$victim missing", s, missing, filesMessage) { s.factory.create(keywordModel(missing)) }
            val empty = modelDir(stamped = false)
            File(empty, victim).writeText("")
            assertRefused("$victim empty", s, empty, filesMessage) { s.factory.create(keywordModel(empty)) }
        }
        val absent = File(tmp.newFolder(), "absent")
        assertRefused("absent directory", s, absent, filesMessage) { s.factory.create(keywordModel(absent)) }
    }

    @Test
    fun `a missing stamp is refused and nothing is written or opened`() {
        val s = setup()
        val dir = modelDir(stamped = false)
        assertRefused("no stamp", s, dir, filesMessage) { s.factory.create(keywordModel(dir)) }
        File(dir, UnpackedStamp.FILE_NAME).writeText("not a stamp\n")
        assertRefused("unparsable stamp", s, dir, filesMessage) { s.factory.create(keywordModel(dir)) }
    }

    @Test
    fun `a stamp that does not match the files is refused and nothing is written or opened`() {
        val s = setup()
        val resized = modelDir()
        File(resized, encoder).writeText("xx")
        assertRefused("size changed", s, resized, filesMessage) { s.factory.create(keywordModel(resized)) }

        val retimed = modelDir()
        val decoderFile = File(retimed, decoder)
        assertTrue("$tag fixture: the modified time moves", decoderFile.setLastModified(decoderFile.lastModified() - 60_000L))
        assertRefused("time changed", s, retimed, filesMessage) { s.factory.create(keywordModel(retimed)) }

        val other = tmp.newFolder()
        for (name in names) File(other, name).writeText(if (name == tokens) tokenText() else "x")
        File(other, "other.txt").writeText("x")
        val stamp = checkNotNull(
            UnpackedStamp.measure(other, listOf(encoder, decoder, joiner, "other.txt"), Fixtures.VALID_PIN) { ModelIntegrity.sha256(it) },
        )
        File(other, UnpackedStamp.FILE_NAME).writeText(stamp.encode())
        assertRefused("stamp over other names", s, other, filesMessage) { s.factory.create(keywordModel(other)) }
    }

    @Test
    fun `a token table that lacks any token of the fixed phrases is refused and nothing is written or opened`() {
        assertTrue("$tag fixture: the fixed phrases name tokens", phraseTokens.isNotEmpty())
        for (lacking in phraseTokens) {
            val s = setup()
            val dir = modelDir(text = tokenText(without = lacking))
            assertRefused("table lacks [$lacking]", s, dir, tokensMessage) { s.factory.create(keywordModel(dir)) }
        }
    }

    @Test
    fun `a token table of exactly the size limit is accepted and one byte more is refused`() {
        val atLimit = tokenText(extraBytes = 0).toByteArray().size
        val extra = (SherpaOnnxKeywordStreamFactory.MAX_TOKENS_BYTES - atLimit).toInt()
        val s = setup()
        val exact = modelDir(text = tokenText(extraBytes = extra))
        assertEquals("$tag fixture: the table is exactly the limit", SherpaOnnxKeywordStreamFactory.MAX_TOKENS_BYTES, File(exact, tokens).length())
        assertNotNull("$tag a table of exactly the limit is accepted", s.factory.create(keywordModel(exact)))

        val over = modelDir(text = tokenText(extraBytes = extra + 1))
        assertEquals("$tag fixture: the table is one byte over", SherpaOnnxKeywordStreamFactory.MAX_TOKENS_BYTES + 1, File(over, tokens).length())
        val refused = refusal("one byte over") { s.factory.create(keywordModel(over)) }
        assertEquals("$tag the fixed message", tokensMessage, refused.message)
        assertEquals("$tag only the accepted model wrote a file", 1, s.writer.texts.size)
    }

    @Test
    fun `a writer that fails refuses with the fixed message and no opener is asked for`() {
        val s = setup(failure = IOException("disk path /secret/place is full"))
        val dir = modelDir()
        val refused = refusal("writer failure") { s.factory.create(keywordModel(dir)) }
        assertEquals("$tag the fixed message", writeMessage, refused.message)
        assertNull("$tag the cause with a path is not passed on", refused.cause)
        assertEquals("$tag the writer was asked once", 1, s.writer.texts.size)
        assertEquals("$tag no opener is asked for after a failed write", 0, s.recorder.keywordFiles.size)
    }

    @Test
    fun `create writes once with the rendered text and asks the opener factory once with the written file`() {
        val s = setup()
        val dir = modelDir()
        val stream = s.factory.create(keywordModel(dir))
        assertEquals("$tag the writer is called once", 1, s.writer.texts.size)
        assertEquals("$tag the text is the rendered fixed phrases", KeywordsFileText.render(FixedPhrases.all()), s.writer.texts.single())
        assertTrue(
            "$tag the wake line is in the written text",
            s.writer.texts.single().contains("${mark}B RE A K ER ${mark}B RE A K ER\n"),
        )
        assertEquals("$tag the opener factory is asked once", 1, s.recorder.keywordFiles.size)
        assertSame("$tag it is asked with the file the writer returned", s.writer.returned, s.recorder.keywordFiles.single())
        assertEquals("$tag create opens no engine", 0, s.recorder.openedFiles.size)
        assertFalse("$tag a new stream is not running", stream.isRunning)
    }

    @Test
    fun `starting the stream opens the engine with the located files and the given thread count`() {
        val s = setup(threads = 3)
        val dir = modelDir()
        val stream = s.factory.create(keywordModel(dir))
        stream.start { }
        stream.feed(timedChunk())
        runBlocking { s.recorder.fake.decoded.receive() }
        stream.stop()
        assertEquals("$tag the engine is opened once", 1, s.recorder.openedFiles.size)
        assertEquals("$tag the thread count reaches the engine", listOf(3), s.recorder.openedThreads)
        val files = s.recorder.openedFiles.single()
        assertEquals("$tag the encoder is the int8 file", File(dir, encoder), files.encoder)
        assertEquals("$tag the decoder is the plain file", File(dir, decoder), files.decoder)
        assertEquals("$tag the joiner is the int8 file", File(dir, joiner), files.joiner)
        assertEquals("$tag the tokens file", File(dir, tokens), files.tokens)
    }

    @Test
    fun `a thread count below one is refused at construction and one is accepted`() {
        for (bad in listOf(0, -1)) {
            try {
                SherpaOnnxKeywordStreamFactory(CountingWriter(File("x")), bad) { NativeStreamingOpener { _, _ -> error("never opened") } }
                fail("$tag numThreads $bad must be refused")
            } catch (expected: IllegalArgumentException) {
                // the refusal is the expected outcome
            }
        }
        assertNotNull(
            "$tag one thread is the first accepted count",
            SherpaOnnxKeywordStreamFactory(CountingWriter(File("x")), 1) { NativeStreamingOpener { _, _ -> error("never opened") } },
        )
    }
}
