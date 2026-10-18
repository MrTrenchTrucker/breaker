package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

/** Records each call to the engine opener and passes it on to the scripted engine. */
private class RecordingOpener(private val fake: FakeTimedNative) : NativeStreamingOpener {
    val files = mutableListOf<TransducerFiles>()
    val threads = mutableListOf<Int>()

    override fun open(files: TransducerFiles, numThreads: Int): NativeStreamingRecognizer {
        this.files.add(files)
        threads.add(numThreads)
        return fake.open(numThreads)
    }
}

/**
 * Tests of the word stream factory, with a scripted engine: nothing here loads the native library.
 * Every wait is a signal. The class Timeout rule is only a safety net.
 */
class SherpaOnnxWordStreamFactoryTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    @get:Rule
    val tmp = TemporaryFolder()

    private val encoderInt8 = "encoder-epoch-99-avg-1.int8.onnx"
    private val decoderPlain = "decoder-epoch-99-avg-1.onnx"
    private val joinerInt8 = "joiner-epoch-99-avg-1.int8.onnx"
    private val tokens = "tokens.txt"
    private val smallNames = listOf(encoderInt8, decoderPlain, joinerInt8, tokens)

    /**
     * A model folder holding [names]. When [stamped] is true the install stamp is written after the files,
     * so the folder reads as an installed model; the refusal fixtures pass false.
     */
    private fun modelDir(names: List<String> = smallNames, stamped: Boolean = true): File {
        val dir = tmp.newFolder()
        for (name in names) File(dir, name).writeText("x")
        if (stamped) {
            val choice = checkNotNull(TransducerFileLocator.choose(names))
            val picked = listOf(choice.encoder, choice.decoder, choice.joiner, choice.tokens)
            val stamp = checkNotNull(UnpackedStamp.measure(dir, picked, Fixtures.VALID_PIN) { ModelIntegrity.sha256(it) })
            File(dir, UnpackedStamp.FILE_NAME).writeText(stamp.encode())
        }
        return dir
    }

    private fun model(id: String, dir: File) = SherpaModel(id, dir, "digest")

    /** Runs [block] and returns what it threw, failing by name when it threw nothing. */
    private fun thrownBy(label: String, block: () -> Unit): Throwable {
        try {
            block()
        } catch (t: Throwable) {
            return t
        }
        fail("$label: nothing was thrown")
        throw IllegalStateException("unreachable")
    }

    private fun refusal(label: String, block: () -> Unit): SherpaTranscriptionException {
        val thrown = thrownBy(label, block)
        assertTrue("$label: expected SherpaTranscriptionException but got $thrown", thrown is SherpaTranscriptionException)
        return thrown as SherpaTranscriptionException
    }

    private fun assertNoLeak(label: String, message: String?, dir: File, modelId: String = "") {
        assertNotNull("$label: the message must exist", message)
        val text = message!!
        assertFalse("$label: the message names the directory: $text", text.contains(dir.path))
        assertFalse("$label: the message names a model file: $text", text.contains(".onnx") || text.contains(tokens))
        if (modelId.isNotBlank()) assertFalse("$label: the message names the model id: $text", text.contains(modelId))
    }

    @Test
    fun `a model id with no streaming profile is refused with the fixed message and the engine is never opened`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val opener = RecordingOpener(fake)
        val factory = SherpaOnnxWordStreamFactory(opener, 2)
        for (id in listOf("base", "medium", "unknown", "", "Small", "TINY", "small ")) {
            val dir = modelDir(stamped = false)
            val refused = refusal("id [$id]") { factory.create(model(id, dir)) }
            assertEquals("id [$id]: the fixed message", "no streaming profile for this model", refused.message)
            assertNoLeak("id [$id]", refused.message, dir, id)
        }
        assertEquals("the engine was opened for an id with no profile", 0, opener.files.size)
        assertEquals("the native engine was touched", 0, fake.calls.size)
    }

    @Test
    fun `a directory missing a file or holding an empty one is refused with the fixed message and never opened`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val opener = RecordingOpener(fake)
        val factory = SherpaOnnxWordStreamFactory(opener, 2)
        for (victim in smallNames) {
            // No stamp here: this folder must be refused for the missing file itself.
            val missing = modelDir(smallNames.filter { it != victim }, stamped = false)
            val gone = refusal("$victim missing") { factory.create(model("small", missing)) }
            assertEquals("$victim missing: the fixed message", "the model files are missing or empty", gone.message)
            assertNoLeak("$victim missing", gone.message, missing, "small")

            // No stamp here: this folder must be refused for the empty file itself.
            val empty = modelDir(stamped = false)
            File(empty, victim).writeText("")
            val blank = refusal("$victim empty") { factory.create(model("small", empty)) }
            assertEquals("$victim empty: the fixed message", "the model files are missing or empty", blank.message)
            assertNoLeak("$victim empty", blank.message, empty, "small")
        }
        val absent = File(tmp.newFolder(), "absent")
        val gone = refusal("absent directory") { factory.create(model("tiny", absent)) }
        assertEquals("the fixed message for an absent directory", "the model files are missing or empty", gone.message)
        assertNoLeak("absent directory", gone.message, absent, "tiny")
        assertEquals("the engine was opened for a directory that cannot be used", 0, opener.files.size)
    }

    @Test
    fun `a thread count below one is refused at construction`() {
        for (bad in listOf(0, -1)) {
            val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
            val thrown = thrownBy("numThreads $bad") { SherpaOnnxWordStreamFactory(RecordingOpener(fake), bad) }
            assertTrue("numThreads $bad: expected IllegalArgumentException but got $thrown", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `create returns a stream for a good directory and opens nothing`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val opener = RecordingOpener(fake)
        val stream = SherpaOnnxWordStreamFactory(opener, 2).create(model("small", modelDir()))
        assertNotNull("create returns a stream", stream)
        assertEquals("the opener was called by create", 0, opener.files.size)
        assertEquals("the native engine was opened by create", 0, fake.count("open"))
        assertFalse("a new stream is not running", stream.isRunning)
    }

    @Test
    fun `the stream opens once with the located files and the given thread count and gives the final words and start times`() {
        val dir = modelDir()
        val fake = FakeTimedNative(
            listOf(timedResult(listOf("${TIMED_MARK}and"), listOf(0.125f))),
            finalResult = timedResult(
                listOf("${TIMED_MARK}and", "${TIMED_MARK}i'm", "${TIMED_MARK}gone"),
                listOf(0.125f, 0.5f, 0.75f),
            ),
        )
        val opener = RecordingOpener(fake)
        val stream = SherpaOnnxWordStreamFactory(opener, 3).create(model("tiny", dir))
        val sink = UpdateSink(1)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()

        assertEquals("the engine was opened exactly once", 1, opener.files.size)
        assertEquals("the given thread count reached the engine", listOf(3), opener.threads)
        val files = opener.files.single()
        assertEquals("encoder is the located int8 file", File(dir, encoderInt8), files.encoder)
        assertEquals("decoder is the located plain file", File(dir, decoderPlain), files.decoder)
        assertEquals("joiner is the located int8 file", File(dir, joinerInt8), files.joiner)
        assertEquals("tokens is the located token table", File(dir, tokens), files.tokens)
        assertEquals("one partial update, then one final", listOf(false, true), sink.updates.map { it.final })
        assertEquals(
            "the final words carry their start times in milliseconds",
            listOf(HeardWord("and", 125L), HeardWord("i'm", 500L), HeardWord("gone", 750L)),
            sink.updates.last().words,
        )
    }

    @Test
    fun `two creates give two streams and starting one leaves the other stopped`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val opener = RecordingOpener(fake)
        val factory = SherpaOnnxWordStreamFactory(opener, 2)
        val dir = modelDir()
        val first = factory.create(model("small", dir))
        val second = factory.create(model("small", dir))
        assertNotSame("each create makes its own stream", first, second)

        first.start { }
        runBlocking { fake.entered("createStream").await() }
        assertTrue("the started stream runs", first.isRunning)
        assertFalse("starting one stream does not start the other", second.isRunning)
        assertEquals("only the started stream opened the engine", 1, opener.files.size)

        first.stop()
        runBlocking { fake.recognizerReleased.await() }
        assertFalse("the stopped stream is not running", first.isRunning)
    }
}
