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

/** Counts each call to the native opener and passes it on to a scripted engine. */
private class StampOpener(private val fake: FakeTimedNative) : NativeStreamingOpener {
    var opened: Int = 0

    override fun open(files: TransducerFiles, numThreads: Int): NativeStreamingRecognizer {
        opened++
        return fake.open(numThreads)
    }
}

/**
 * The word stream factory and the install stamp: a model folder with no usable stamp is
 * refused before anything is opened, a matching stamp is accepted, and a refusal deletes nothing.
 */
class SherpaOnnxWordStreamFactoryStampTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val encoder = "encoder-epoch-99-avg-1.int8.onnx"
    private val decoder = "decoder-epoch-99-avg-1.onnx"
    private val joiner = "joiner-epoch-99-avg-1.int8.onnx"
    private val tokens = "tokens.txt"
    private val names = listOf(encoder, decoder, joiner, tokens)
    private val digest = Fixtures.VALID_PIN
    private val fixedSentence = "the model files are missing or empty"

    private fun modelDir(): File {
        val dir = tmp.newFolder()
        for (name in names) File(dir, name).writeText("x")
        return dir
    }

    private fun model(dir: File) = SherpaModel("tiny", dir, "digest")

    private fun newOpener(): StampOpener = StampOpener(FakeTimedNative(listOf(TIMED_NO_WORDS)))

    private fun factoryWith(opener: StampOpener) = SherpaOnnxWordStreamFactory(opener, 2)

    /** Runs [block] and returns the refusal it threw; a call that does not refuse fails by name. */
    private fun refusal(label: String, block: () -> Unit): SherpaTranscriptionException {
        try {
            block()
        } catch (e: SherpaTranscriptionException) {
            return e
        }
        fail("$label: expected a refusal but nothing was refused")
        throw IllegalStateException("unreachable")
    }

    /** Writes the stamp measured from the files in [dir] for [stampNames], the real digests included. */
    private fun writeStamp(dir: File, stampNames: List<String> = names) {
        val stamp = checkNotNull(UnpackedStamp.measure(dir, stampNames, digest) { ModelIntegrity.sha256(it) }) {
            "fixture: the files must measure"
        }
        File(dir, UnpackedStamp.FILE_NAME).writeText(stamp.encode())
    }

    /** A stamp text written by the test itself. [wrongSizeOf] adds one byte to the recorded size of that file. */
    private fun handWrittenStamp(dir: File, wrongSizeOf: String? = null): String {
        val lines = ArrayList<String>()
        lines.add("unpacked-stamp 1")
        lines.add("archive $digest")
        for (name in names) {
            val file = File(dir, name)
            val extra = if (name == wrongSizeOf) 1L else 0L
            val sha = Fixtures.independentSha256(file.readBytes())
            lines.add("file $name ${file.length() + extra} ${file.lastModified()} $sha")
        }
        lines.add("end")
        return lines.joinToString("\n", postfix = "\n")
    }

    @Test
    fun `a model folder with no stamp is refused and the opener is never called`() {
        val opener = newOpener()
        val dir = modelDir()

        val refused = refusal("no stamp") { factoryWith(opener).create(model(dir)) }

        assertEquals("the fixed sentence", fixedSentence, refused.message)
        assertEquals("the opener must not be called", 0, opener.opened)
    }

    @Test
    fun `a stamp that does not parse is refused and the opener is never called`() {
        val opener = newOpener()
        val dir = modelDir()
        File(dir, UnpackedStamp.FILE_NAME).writeText("not a stamp\n")

        val refused = refusal("unparsable stamp") { factoryWith(opener).create(model(dir)) }

        assertEquals("the fixed sentence", fixedSentence, refused.message)
        assertEquals("the opener must not be called", 0, opener.opened)
    }

    @Test
    fun `a stamp whose file has another size is refused and the opener is never called`() {
        val opener = newOpener()
        val dir = modelDir()
        writeStamp(dir)
        File(dir, encoder).writeText("xx")

        val refused = refusal("size changed") { factoryWith(opener).create(model(dir)) }

        assertEquals("the fixed sentence", fixedSentence, refused.message)
        assertEquals("the opener must not be called", 0, opener.opened)
    }

    @Test
    fun `a stamp with another modified time is refused without hashing`() {
        val opener = newOpener()
        val dir = modelDir()
        writeStamp(dir)
        val decoderFile = File(dir, decoder)
        assertTrue("fixture: the modified time moves", decoderFile.setLastModified(decoderFile.lastModified() - 60_000L))

        val refused = refusal("time changed, content intact") { factoryWith(opener).create(model(dir)) }

        assertEquals("the fixed sentence", fixedSentence, refused.message)
        assertEquals("the opener must not be called", 0, opener.opened)
    }

    @Test
    fun `a stamp that does not cover the chosen files is refused and the opener is never called`() {
        val opener = newOpener()
        val dir = modelDir()
        File(dir, "other.txt").writeText("x")
        writeStamp(dir, listOf(encoder, decoder, joiner, "other.txt"))

        val refused = refusal("stamp over other names") { factoryWith(opener).create(model(dir)) }

        assertEquals("the fixed sentence", fixedSentence, refused.message)
        assertEquals("the opener must not be called", 0, opener.opened)
    }

    @Test
    fun `a matching stamp creates the stream and opens nothing`() {
        val opener = newOpener()
        val dir = modelDir()
        writeStamp(dir)

        val stream = factoryWith(opener).create(model(dir))

        assertNotNull("the stream is made", stream)
        assertEquals("nothing is opened by create", 0, opener.opened)
        assertFalse("the new stream is not running", stream.isRunning)
    }

    @Test
    fun `the refusal carries the fixed sentence with no path, file name or model id`() {
        val dir = modelDir()
        writeStamp(dir)
        File(dir, encoder).writeText("xx")

        val refused = refusal("size changed") { factoryWith(newOpener()).create(model(dir)) }
        val text = checkNotNull(refused.message) { "the refusal must carry a message" }

        assertEquals("the fixed sentence", fixedSentence, text)
        assertFalse("the message names the directory: $text", text.contains(dir.path))
        for (name in names) assertFalse("the message names $name: $text", text.contains(name))
        assertFalse("the message names the model id: $text", text.contains("tiny"))
    }

    @Test
    fun `a refusal deletes nothing`() {
        val opener = newOpener()
        val dir = modelDir()
        writeStamp(dir)
        File(dir, encoder).writeText("changed content")

        refusal("size changed") { factoryWith(opener).create(model(dir)) }

        for (name in names) assertTrue("$name stays on disk", File(dir, name).isFile)
        assertTrue("the stamp stays on disk", File(dir, UnpackedStamp.FILE_NAME).isFile)
        assertEquals("the changed file keeps its content", "changed content", File(dir, encoder).readText())
        assertEquals("the opener must not be called", 0, opener.opened)
    }

    @Test
    fun `a stamp written by hand from independently computed values creates the stream`() {
        val opener = newOpener()
        val dir = modelDir()
        File(dir, UnpackedStamp.FILE_NAME).writeText(handWrittenStamp(dir))

        val stream = factoryWith(opener).create(model(dir))

        assertNotNull("the stream is made", stream)
        assertEquals("nothing is opened by create", 0, opener.opened)
    }

    @Test
    fun `a hand written stamp with one wrong size is refused and the opener is never called`() {
        val opener = newOpener()
        val dir = modelDir()
        File(dir, UnpackedStamp.FILE_NAME).writeText(handWrittenStamp(dir, wrongSizeOf = joiner))

        val refused = refusal("hand written, one size off") { factoryWith(opener).create(model(dir)) }

        assertEquals("the fixed sentence", fixedSentence, refused.message)
        assertEquals("the opener must not be called", 0, opener.opened)
    }
}
