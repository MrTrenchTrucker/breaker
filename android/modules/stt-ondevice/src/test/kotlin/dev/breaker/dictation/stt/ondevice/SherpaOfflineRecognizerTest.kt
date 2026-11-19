package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A scripted offline engine: it records every call and can be told to fail. Nothing native is loaded. */
internal class FakeOfflineNative(
    private val text: String = "hello there",
    private val language: String? = null,
    private val decodeError: Throwable? = null,
    private val releaseError: Throwable? = null,
) : NativeOfflineRecognizer {
    /** Length, sample sum and rate of each decode call, in order. */
    val decodes = mutableListOf<Triple<Int, Double, Int>>()
    var releases = 0

    override fun decode(samples: FloatArray, sampleRateHz: Int): NativeOfflineResult {
        decodes.add(Triple(samples.size, samples.sumOf { it.toDouble() }, sampleRateHz))
        if (decodeError != null) throw decodeError
        return NativeOfflineResult(text, language)
    }

    override fun release() {
        releases++
        if (releaseError != null) throw releaseError
    }
}

/** Tests of the offline recognizer over a fake engine. Every mutant named here is hand traced, not run. */
class SherpaOfflineRecognizerTest {

    private fun clip(samples: Int) = FloatArray(samples) { (it % 7 + 1).toFloat() }

    private fun refusal(block: () -> Unit): SherpaTranscriptionException {
        try {
            block()
        } catch (e: SherpaTranscriptionException) {
            return e
        }
        fail("nothing was thrown")
        throw IllegalStateException("unreachable")
    }

    @Test
    fun `one take is one native decode that receives the whole clip and nothing else`() {
        val native = FakeOfflineNative()
        val pcm = clip(48_007)
        SherpaOfflineRecognizer(native, "").decode(pcm, 16_000)
        // Killer of a recogniser that pads the clip with silence (length 48007 + padding fails the length assertion)
        // and of one that decodes twice (the size assertion fails).
        assertEquals("exactly one native decode per take", 1, native.decodes.size)
        val (length, sum, rate) = native.decodes.single()
        assertEquals("the whole clip, no tail padding", pcm.size, length)
        assertEquals("the clip's own samples", pcm.sumOf { it.toDouble() }, sum, 0.0)
        assertEquals("16 kHz", 16_000, rate)
    }

    @Test
    fun `a failing decode is reported with a fixed message and the cause and the engine is still released once`() {
        val modelPath = "/data/user/0/app/files/encoder.int8.onnx"
        for (error in listOf<Throwable>(IllegalStateException("bad $modelPath"), UnsatisfiedLinkError("link $modelPath"))) {
            val native = FakeOfflineNative(decodeError = error)
            val recognizer = SherpaOfflineRecognizer(native, "")
            val refused = refusal { recognizer.decode(clip(1_600), 16_000) }
            assertSame("the cause is the original failure", error, refused.cause)
            assertFalse("the message repeats the engine text: ${refused.message}", refused.message!!.contains(modelPath))
            assertEquals("the message is the fixed one", "the on-device decode failed", refused.message)
            // The engine wrap releases in its finally; the release must still reach the engine once.
            recognizer.release()
            assertEquals("released once after a failed decode", 1, native.releases)
        }
    }

    @Test
    fun `release frees the engine once after a good take, twice is harmless and a throwing release never escapes`() {
        val native = FakeOfflineNative(releaseError = IllegalStateException("release failed"))
        val recognizer = SherpaOfflineRecognizer(native, "")
        recognizer.decode(clip(1_600), 16_000)
        assertEquals("a decode does not release", 0, native.releases)
        recognizer.release()
        recognizer.release()
        // Killer of a release that is not guarded by the flag (count 2) and of one that lets the failure escape.
        assertEquals("the engine is released exactly once", 1, native.releases)
        val linkage = FakeOfflineNative(releaseError = NoClassDefFoundError("gone"))
        SherpaOfflineRecognizer(linkage, "").release()
        assertEquals("a LinkageError from release is swallowed too", 1, linkage.releases)
    }

    @Test
    fun `a refused call never reaches the engine`() {
        val native = FakeOfflineNative()
        val recognizer = SherpaOfflineRecognizer(native, "")
        refusal { recognizer.decode(clip(1_600), 8_000) }
        refusal { recognizer.decode(FloatArray(0), 16_000) }
        recognizer.release()
        refusal { recognizer.decode(clip(1_600), 16_000) }
        assertTrue("the engine was called for a refused clip: ${native.decodes}", native.decodes.isEmpty())
    }

    @Test
    fun `the transcript is one segment over the clip and a blank text gives none`() {
        val pcm = clip(24_000)
        val spoken = SherpaOfflineRecognizer(FakeOfflineNative(text = "  Hello there.  "), "").decode(pcm, 16_000)
        assertEquals("trimmed text", "Hello there.", spoken.text)
        assertEquals("one segment", 1, spoken.segments.size)
        assertEquals("segment start", 0L, spoken.segments.single().startMs)
        assertEquals("segment end is the clip length in ms", 1_500L, spoken.segments.single().endMs)
        val blank = SherpaOfflineRecognizer(FakeOfflineNative(text = "   "), "").decode(pcm, 16_000)
        assertEquals("", blank.text)
        assertTrue("no segment for a blank text", blank.segments.isEmpty())
    }

    @Test
    fun `the language is the engine's when it reports one, else the profile's, and may stay blank`() {
        val pcm = clip(1_600)
        assertEquals("de", SherpaOfflineRecognizer(FakeOfflineNative(language = " de "), "").decode(pcm, 16_000).language)
        assertEquals("en", SherpaOfflineRecognizer(FakeOfflineNative(language = null), "en").decode(pcm, 16_000).language)
        assertEquals("en", SherpaOfflineRecognizer(FakeOfflineNative(language = "  "), "en").decode(pcm, 16_000).language)
        // Blank means the model decides; the engine wrap fills in the request language (OnDeviceSttEngine ifBlank).
        assertEquals("", SherpaOfflineRecognizer(FakeOfflineNative(language = null), "").decode(pcm, 16_000).language)
    }

    @Test
    fun `an all capital text is normalised and a cased text is left alone`() {
        val pcm = clip(1_600)
        assertEquals("hello I am here", SherpaOfflineRecognizer(FakeOfflineNative(text = "HELLO I AM HERE"), "").decode(pcm, 16_000).text)
        assertEquals("Hello, I am here.", SherpaOfflineRecognizer(FakeOfflineNative(text = "Hello, I am here."), "").decode(pcm, 16_000).text)
    }
}
