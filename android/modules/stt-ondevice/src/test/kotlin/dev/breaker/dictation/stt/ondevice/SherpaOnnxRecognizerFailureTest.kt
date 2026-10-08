package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * What the adapter does when the native engine fails, and how it is released. A fake
 * engine throws on a chosen call; no library is loaded.
 */
class SherpaOnnxRecognizerFailureTest {
    // A net for the failure arm only: no test relies on it, and the expected red is an assertion that names the test.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val rate = 16_000

    /** The native calls a decode makes, as (method name, which call of it), in the order they happen. */
    private val decodeCalls = listOf(
        "createStream" to 1,
        "acceptWaveform" to 1,
        "acceptWaveform" to 2,
        "inputFinished" to 1,
        "isReady" to 1,
        "decode" to 1,
        "text" to 1,
    )

    private fun scripted() = ScriptedNative(decodesNeeded = 1, text = "hello")

    @Test
    fun `a native exception during decode becomes SherpaTranscriptionException and the stream is released`() {
        for ((call, nth) in decodeCalls) {
            val label = "$call call $nth"
            val native = scripted()
            val boom = IllegalStateException("cannot open /secret/path/model.onnx")
            native.failOn(call, boom, nth)
            val failure = decodeFailure(label, SherpaOnnxRecognizer(native, "en"))
            assertSame("$label: the native failure travels as the cause", boom, failure.cause)
            assertFalse("$label: the message carries no path", failure.message.orEmpty().contains("secret"))
            assertTrue("$label: the message is not blank", failure.message.orEmpty().isNotBlank())
            val streamMade = if (call == "createStream") 0 else 1
            assertEquals("$label: stream releases", streamMade, native.count("streamRelease"))
            assertEquals("$label: the recognizer is not released by a decode", 0, native.count("release"))
        }
    }

    @Test
    fun `decode does not release the native recognizer and release does so exactly once`() {
        val ok = scripted()
        val adapter = SherpaOnnxRecognizer(ok, "en")
        decodeOk("a good decode", adapter)
        assertEquals("a good decode leaves the recognizer alone", 0, ok.count("release"))
        val broken = scripted()
        broken.failOn("decode", IllegalStateException("boom"))
        val failing = SherpaOnnxRecognizer(broken, "en")
        decodeFailure("a failing decode", failing)
        assertEquals("a failed decode leaves the recognizer alone", 0, broken.count("release"))
        adapter.release()
        adapter.release()
        failing.release()
        failing.release()
        assertEquals("release frees the recognizer once", 1, ok.count("release"))
        assertEquals("release frees the recognizer once after a failed decode", 1, broken.count("release"))
    }

    @Test
    fun `an UnsatisfiedLinkError and a NoClassDefFoundError from a native call become SherpaTranscriptionException`() {
        val errors = listOf<() -> Throwable>(
            { UnsatisfiedLinkError("no native library") },
            { NoClassDefFoundError("missing class") },
        )
        for (makeError in errors) {
            for ((call, nth) in decodeCalls) {
                val error = makeError()
                val label = "${error.javaClass.simpleName} on $call call $nth"
                val native = scripted()
                native.failOn(call, error, nth)
                val failure = decodeFailure(label, SherpaOnnxRecognizer(native, "en"))
                assertSame("$label: the link error travels as the cause", error, failure.cause)
                assertFalse(
                    "$label: the message does not repeat the link error text",
                    failure.message.orEmpty().contains(error.message.orEmpty()),
                )
                val streamMade = if (call == "createStream") 0 else 1
                assertEquals("$label: stream releases", streamMade, native.count("streamRelease"))
            }
        }
    }

    @Test
    fun `a release that throws is swallowed and a second release does nothing`() {
        val errors = listOf<() -> Throwable>(
            { IllegalStateException("release failed") },
            { UnsatisfiedLinkError("release failed to link") },
        )
        for (makeError in errors) {
            val error = makeError()
            val label = error.javaClass.simpleName
            val native = ScriptedNative()
            native.failOn("release", error)
            val adapter = SherpaOnnxRecognizer(native, "en")
            mustNotThrow("$label: first release") { adapter.release() }
            mustNotThrow("$label: second release") { adapter.release() }
            assertEquals("$label: the native release was tried once", 1, native.count("release"))
        }
    }

    @Test
    fun `decode after release is refused and never reaches the native recognizer`() {
        val native = scripted()
        val adapter = SherpaOnnxRecognizer(native, "en")
        adapter.release()
        val refused = decodeFailure("a decode after release", adapter)
        assertTrue("the refusal message is not blank", refused.message.orEmpty().isNotBlank())
        assertEquals("only the release reached the engine", listOf("release"), native.log)
    }

    @Test
    fun `release on an adapter that never decoded releases the native recognizer once`() {
        val native = scripted()
        SherpaOnnxRecognizer(native, "en").release()
        assertEquals("the log", listOf("release"), native.log)
    }

    @Test
    fun `a stream release that throws does not replace the transcript`() {
        val native = scripted()
        native.failOn("streamRelease", IllegalStateException("stream release failed"))
        val transcript = decodeOk("failing stream release", SherpaOnnxRecognizer(native, "en"))
        assertEquals("the transcript survives a failing stream release", "hello", transcript.text)
        assertEquals("the stream release was tried once", 1, native.count("streamRelease"))
        val linked = scripted()
        linked.failOn("streamRelease", UnsatisfiedLinkError("stream release failed to link"))
        assertEquals("a link error from the release is swallowed too", "hello", decodeOk("link error on stream release", SherpaOnnxRecognizer(linked, "en")).text)
    }

    @Test
    fun `a stream release that throws does not hide the decode failure`() {
        val native = scripted()
        val real = IllegalStateException("the real decode failure")
        native.failOn("decode", real)
        native.failOn("streamRelease", IllegalStateException("stream release failed"))
        val failure = decodeFailure("decode and stream release both fail", SherpaOnnxRecognizer(native, "en"))
        assertSame("the cause is the decode failure and not the release failure", real, failure.cause)
    }

    @Test
    fun `an error thrown by the stream release passes through decode and is not swallowed`() {
        val errors = listOf<() -> Throwable>({ OutOfMemoryError("stream release memory") }, { StackOverflowError() })
        for (makeError in errors) {
            val error = makeError()
            val label = error.javaClass.simpleName
            val native = scripted()
            native.failOn("streamRelease", error)
            val thrown = failureOf(label) { SherpaOnnxRecognizer(native, "en").decode(halfClip(1_600), rate) }
            assertSame("$label: the error from the stream release reaches the caller as it is", error, thrown)
            assertEquals("$label: the stream release was tried once", 1, native.count("streamRelease"))
        }
    }

    @Test
    fun `an OutOfMemoryError and a StackOverflowError from native pass through unchanged`() {
        val errors = listOf<() -> Throwable>({ OutOfMemoryError("native memory") }, { StackOverflowError() })
        for (makeError in errors) {
            val error = makeError()
            val label = error.javaClass.simpleName
            val native = scripted()
            native.failOn("isReady", error)
            val thrown = failureOf(label) { SherpaOnnxRecognizer(native, "en").decode(halfClip(1_600), rate) }
            assertSame("$label: passes through decode as it is", error, thrown)
            assertEquals("$label: the stream is still released", 1, native.count("streamRelease"))
            val releasing = ScriptedNative()
            releasing.failOn("release", error)
            val adapter = SherpaOnnxRecognizer(releasing, "en")
            assertSame("$label: passes through release as it is", error, failureOf(label) { adapter.release() })
            mustNotThrow("$label: second release") { adapter.release() }
            assertEquals("$label: the flag was set before the native call", 1, releasing.count("release"))
            assertTrue("$label: decode is refused after a release that threw", failureOf(label) { adapter.decode(halfClip(1_600), rate) } is SherpaTranscriptionException)
        }
    }
}
