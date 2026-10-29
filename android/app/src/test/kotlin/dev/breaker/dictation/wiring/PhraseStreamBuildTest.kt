package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.ModelLoader
import dev.breaker.dictation.stt.ondevice.SherpaModel
import dev.breaker.dictation.stt.ondevice.SherpaRecognizer
import dev.breaker.dictation.stt.ondevice.SherpaTranscript
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * The build of a phrase word stream from one load: the loaded recognizer is released once, the stream is made
 * from the loaded model, and a refused load, a load that throws or a stream that cannot be made gives null.
 * The recognizer is a fake that counts its releases and the stream is the shared fake, so no native engine opens.
 */
internal class PhraseStreamBuildTest {

    /** A recognizer that counts how many times it was released. */
    private class CountingRecognizer : SherpaRecognizer {
        var releases: Int = 0

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript =
            SherpaTranscript("", emptyList(), "en")

        override fun release() {
            releases += 1
        }
    }

    private val model: SherpaModel = SherpaModel("tiny", File("models/tiny"), "00")

    @Test
    fun `a ready load releases the recognizer once and makes the stream from the loaded model`() {
        val recognizer = CountingRecognizer()
        var made: SherpaModel? = null
        buildPhraseStream("tiny", { ModelLoader.LoadResult.Ready(model, recognizer) }) { loaded ->
            made = loaded
            PhraseTestStream()
        }
        assertSame("app: the stream must be made from the loaded model", model, made)
        assertEquals("app: the recognizer must be released exactly once", 1, recognizer.releases)
    }

    @Test
    fun `a stream that cannot be made gives null and the recognizer is still released once`() {
        val recognizer = CountingRecognizer()
        val result = buildPhraseStream("tiny", { ModelLoader.LoadResult.Ready(model, recognizer) }) {
            throw IllegalStateException("no stream")
        }
        assertNull("app: a stream that cannot be made must give null", result)
        assertEquals("app: the recognizer must be released once when the stream fails", 1, recognizer.releases)
    }

    @Test
    fun `a refused load gives null and never makes a stream`() {
        var made = 0
        val result = buildPhraseStream(
            "tiny",
            { ModelLoader.LoadResult.Refused(ModelLoader.Refusal.NOT_INSTALLED, "fixed sentence") },
        ) {
            made += 1
            PhraseTestStream()
        }
        assertNull("app: a refused load must give null", result)
        assertEquals("app: a refused load must not make a stream", 0, made)
    }

    @Test
    fun `a load that throws gives null and makes no stream`() {
        var made = 0
        val result = buildPhraseStream("tiny", { throw IllegalStateException("store unreadable") }) {
            made += 1
            PhraseTestStream()
        }
        assertNull("app: a load that throws must give null", result)
        assertEquals("app: a load that throws must not make a stream", 0, made)
    }

    @Test
    fun `the result is the stream that create returned`() {
        val stream = PhraseTestStream()
        val result = buildPhraseStream("tiny", { ModelLoader.LoadResult.Ready(model, CountingRecognizer()) }) { stream }
        assertSame("app: the result must be the stream that create returned", stream, result)
    }

    @Test
    fun `a virtual machine error from load is not swallowed`() {
        var made = 0
        try {
            buildPhraseStream("tiny", { throw OutOfMemoryError("the virtual machine is out of memory") }) {
                made += 1
                PhraseTestStream()
            }
            fail("app: a virtual machine error from load should reach the caller")
        } catch (e: OutOfMemoryError) {
            assertEquals("app: a virtual machine error from load must not make a stream", 0, made)
        }
    }

    @Test
    fun `a virtual machine error from create is not swallowed`() {
        val recognizer = CountingRecognizer()
        try {
            buildPhraseStream("tiny", { ModelLoader.LoadResult.Ready(model, recognizer) }) {
                throw OutOfMemoryError("the virtual machine is out of memory")
            }
            fail("app: a virtual machine error from create should reach the caller")
        } catch (e: OutOfMemoryError) {
            assertEquals("app: the recognizer must be released once before the error reaches the caller", 1, recognizer.releases)
        }
    }
}
