package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** A stream that hears nothing and answers with a fixed text. */
internal class FactoryProbeStream(private val answer: String) : NativeStream {
    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) = Unit

    override fun inputFinished() = Unit

    override fun isReady(): Boolean = false

    override fun decode() = Unit

    override fun text(): String = answer

    override fun release() = Unit
}

/** An engine that counts how often it is released. */
internal class FactoryProbeNative(private val answer: String = "") : NativeStreamingRecognizer {
    var releases = 0

    override fun createStream(): NativeStream = FactoryProbeStream(answer)

    override fun release() {
        releases++
    }
}

/** The opener under test: it records each call and then does what [behaviour] says. */
internal class FactoryProbeOpener(
    private val behaviour: (TransducerFiles, Int) -> NativeStreamingRecognizer,
) : NativeStreamingOpener {
    val files = mutableListOf<TransducerFiles>()
    val threads = mutableListOf<Int>()

    override fun open(files: TransducerFiles, numThreads: Int): NativeStreamingRecognizer {
        this.files.add(files)
        threads.add(numThreads)
        return behaviour(files, numThreads)
    }
}

/**
 * Tests of the recognizer factory, with a fake opener: nothing here opens a
 * native engine or needs the sherpa classes.
 */
class SherpaOnnxRecognizerFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val encoderInt8 = "encoder-epoch-99-avg-1.int8.onnx"
    private val encoderPlain = "encoder-epoch-99-avg-1.onnx"
    private val decoderInt8 = "decoder-epoch-99-avg-1.int8.onnx"
    private val decoderPlain = "decoder-epoch-99-avg-1.onnx"
    private val joinerInt8 = "joiner-epoch-99-avg-1.int8.onnx"
    private val joinerPlain = "joiner-epoch-99-avg-1.onnx"
    private val tokens = "tokens.txt"
    private val smallNames = listOf(encoderInt8, decoderPlain, joinerInt8, tokens)

    private fun modelDir(names: List<String> = smallNames): File {
        val dir = tmp.newFolder()
        for (name in names) File(dir, name).writeText("x")
        return dir
    }

    private fun model(id: String, dir: File) = SherpaModel(id, dir, "digest")

    private fun okOpener() = FactoryProbeOpener { _, _ -> FactoryProbeNative() }

    private fun failingOpener(thrown: Throwable) = FactoryProbeOpener { _, _ -> throw thrown }

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
        assertTrue("$label: the message must not be empty", text.isNotBlank())
        assertFalse("$label: the message names the directory: $text", text.contains(dir.path))
        assertFalse("$label: the message names a model file: $text", text.contains(".onnx") || text.contains(tokens))
        if (modelId.isNotBlank()) assertFalse("$label: the message names the model id: $text", text.contains(modelId))
    }

    // --- Y1 ---

    @Test
    fun `create refuses a model id with no streaming profile and never calls the opener`() {
        val opener = okOpener()
        val factory = SherpaOnnxRecognizerFactory(opener, 2)
        for (id in listOf("base", "medium", "unknown", "", "Small", "TINY", "small ")) {
            val dir = modelDir()
            val refused = refusal("id [$id]") { factory.create(model(id, dir)) }
            assertNoLeak("id [$id]", refused.message, dir, id)
        }
        assertEquals("the opener was called for an id with no profile", 0, opener.files.size)
    }

    // --- Y2 ---

    @Test
    fun `create passes the located files and the thread count to the opener`() {
        val dir = modelDir(listOf(encoderPlain, encoderInt8, decoderInt8, decoderPlain, joinerInt8, joinerPlain, tokens))
        for (threads in listOf(1, 3, 7)) {
            val opener = okOpener()
            SherpaOnnxRecognizerFactory(opener, threads).create(model("small", dir))
            assertEquals("one open per create", 1, opener.files.size)
            assertEquals("the thread count is the one the factory was given", listOf(threads), opener.threads)
            val files = opener.files.single()
            assertEquals("encoder: int8 preferred", File(dir, encoderInt8), files.encoder)
            assertEquals("decoder: plain preferred", File(dir, decoderPlain), files.decoder)
            assertEquals("joiner: plain preferred", File(dir, joinerPlain), files.joiner)
            assertEquals("tokens", File(dir, tokens), files.tokens)
        }
    }

    @Test
    fun `the recognizer create returns is bound to the opened engine and the profile language`() {
        val native = FactoryProbeNative(answer = "hello")
        val factory = SherpaOnnxRecognizerFactory(FactoryProbeOpener { _, _ -> native }, 2)
        for (id in listOf("small", "tiny")) {
            val recognizer = factory.create(model(id, modelDir()))
            assertTrue("$id: the recognizer is the on-device one", recognizer is SherpaOnnxRecognizer)
            val transcript = recognizer.decode(FloatArray(1_600), 16_000)
            assertEquals("$id: text comes from the opened engine", "hello", transcript.text)
            assertEquals("$id: language is the profile language", "en", transcript.language)
        }
        val before = native.releases
        factory.create(model("tiny", modelDir())).release()
        assertEquals("release reaches the opened engine once", before + 1, native.releases)
    }

    // --- Y3 ---

    @Test
    fun `create refuses a directory with a missing or empty file before calling the opener`() {
        val opener = okOpener()
        val factory = SherpaOnnxRecognizerFactory(opener, 2)
        for (victim in smallNames) {
            val missing = modelDir(smallNames.filter { it != victim })
            val gone = refusal("$victim missing") { factory.create(model("small", missing)) }
            assertNoLeak("$victim missing", gone.message, missing)

            val empty = modelDir()
            File(empty, victim).writeText("")
            val blank = refusal("$victim empty") { factory.create(model("small", empty)) }
            assertNoLeak("$victim empty", blank.message, empty)
        }
        val absent = File(tmp.newFolder(), "absent")
        assertNoLeak("absent directory", refusal("absent directory") { factory.create(model("tiny", absent)) }.message, absent)
        assertEquals("the opener was called for a directory that cannot be used", 0, opener.files.size)
    }

    // --- Y4 ---

    @Test
    fun `an UnsatisfiedLinkError and a NoClassDefFoundError from the opener become SherpaTranscriptionException`() {
        val secret = "/data/user/0/app/lib/libsherpa-onnx-jni.so"
        val errors = listOf<Throwable>(
            UnsatisfiedLinkError("cannot load $secret"),
            NoClassDefFoundError("com/example/Missing at $secret"),
            ExceptionInInitializerError("init failed at $secret"),
        )
        for (error in errors) {
            val dir = modelDir()
            val refused = refusal(error.javaClass.simpleName) {
                SherpaOnnxRecognizerFactory(failingOpener(error), 2).create(model("tiny", dir))
            }
            assertSame("the cause is the original error", error, refused.cause)
            assertNoLeak(error.javaClass.simpleName, refused.message, dir)
            assertFalse("the message repeats the error text", refused.message!!.contains(secret))
        }
    }

    // --- Y7 ---

    @Test
    fun `a numThreads below 1 is refused at construction`() {
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            val thrown = thrownBy("numThreads $bad") { SherpaOnnxRecognizerFactory(okOpener(), bad) }
            assertTrue("numThreads $bad: expected IllegalArgumentException but got $thrown", thrown is IllegalArgumentException)
        }
        SherpaOnnxRecognizerFactory(okOpener(), 1)
        assertTrue("the default thread count must be usable", SherpaOnnxRecognizerFactory.DEFAULT_NUM_THREADS >= 1)
    }

    // --- Y10 ---

    @Test
    fun `an Exception from the opener becomes SherpaTranscriptionException with the original as cause`() {
        val errors = listOf<Exception>(
            IllegalStateException("native refused"),
            IOException("disk"),
            RuntimeException("odd"),
            SherpaTranscriptionException("inner failure"),
        )
        for (error in errors) {
            val dir = modelDir()
            val refused = refusal(error.javaClass.simpleName) {
                SherpaOnnxRecognizerFactory(failingOpener(error), 2).create(model("small", dir))
            }
            assertSame("the cause is the original exception", error, refused.cause)
            assertNoLeak(error.javaClass.simpleName, refused.message, dir)
        }
    }

    @Test
    fun `an out of memory error from the opener passes through unchanged`() {
        val error = OutOfMemoryError("injected")
        val thrown = thrownBy("out of memory") {
            SherpaOnnxRecognizerFactory(failingOpener(error), 2).create(model("small", modelDir()))
        }
        assertSame("the error must reach the caller as it was", error, thrown)
    }

    @Test
    fun `a create that failed at the opener can be repeated on the same factory`() {
        var calls = 0
        val opener = FactoryProbeOpener { _, _ ->
            calls++
            if (calls == 1) throw IllegalStateException("first open fails")
            FactoryProbeNative()
        }
        val factory = SherpaOnnxRecognizerFactory(opener, 2)
        val dir = modelDir()
        refusal("first create") { factory.create(model("small", dir)) }
        assertNotNull("the second create must succeed", factory.create(model("small", dir)))
        assertEquals("both attempts reached the opener", 2, opener.files.size)
    }
}
