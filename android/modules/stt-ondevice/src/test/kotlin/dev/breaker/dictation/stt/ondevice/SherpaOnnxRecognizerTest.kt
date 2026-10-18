package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * What the adapter feeds the native engine, in what order, how long it polls, and what
 * transcript it builds. A fake engine records every call; no library is loaded.
 */
class SherpaOnnxRecognizerTest {
    // A net for the failure arm only: no test relies on it, and the expected red is an assertion that names the test.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    @Test
    fun `decode feeds the clip then the tail padding then finishes the stream then polls`() {
        val native = ScriptedNative(decodesNeeded = 1, text = "hello")
        decodeOk("feed order", SherpaOnnxRecognizer(native, "en", tailPaddingSamples = 320))
        val expected = listOf(
            "createStream",
            "acceptWaveform 1600 @16000",
            "acceptWaveform 320 @16000",
            "inputFinished",
            "isReady",
            "decode",
            "isReady",
            "text",
            "streamRelease",
        )
        assertEquals("the exact order of native calls", expected, native.log)
        assertEquals("the lengths of the two fed blocks", listOf(1_600, 320), native.fed.map { it.length })
        assertEquals("the clip is fed as it is and the padding is silence", listOf(800.0, 0.0), native.fed.map { it.sum })
    }

    @Test
    fun `the default tail padding is the named constant and it is not empty`() {
        val native = ScriptedNative()
        decodeOk("default padding", SherpaOnnxRecognizer(native, "en"))
        assertTrue("the placeholder padding must be longer than zero", SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES > 0)
        assertEquals(
            "the default padding length",
            listOf(1_600, SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES),
            native.fed.map { it.length },
        )
    }

    @Test
    fun `the decode loop runs until the stream stops reporting ready`() {
        val native = ScriptedNative(decodesNeeded = 5, text = "five steps")
        val transcript = decodeOk("five steps", SherpaOnnxRecognizer(native, "en"))
        assertEquals("one native decode call per step the engine asked for", 5, native.count("decode"))
        assertEquals("one poll per step plus the last one that said no", 6, native.count("isReady"))
        assertEquals("the text comes from the engine", "five steps", transcript.text)
    }

    @Test
    fun `a stream that never stops reporting ready fails after the step bound`() {
        val native = ScriptedNative(neverReady = true, pollGuard = 28)
        val adapter = SherpaOnnxRecognizer(native, "en", maxSteps = { 20 })
        val failure = decodeFailure("a stream that is always ready", adapter)
        assertEquals("steps taken before the refusal equal the bound", 20, native.count("decode"))
        assertEquals("the poll after the last allowed step is the one that refuses", 21, native.count("isReady"))
        assertEquals("the stream is released after the refusal", 1, native.count("streamRelease"))
        assertEquals("no text is read after a refusal", 0, native.count("text"))
        assertTrue("the failure carries a fixed message", failure.message.orEmpty().isNotEmpty())
        assertNull("the refusal is thrown as it is and not wrapped again", failure.cause)
    }

    @Test
    fun `the default step bound is the frames of the clip and the padding plus sixteen`() {
        val clip = 1_600
        val padded = 320
        val withDefaultPadding = ScriptedNative(neverReady = true)
        decodeFailure("default padding", SherpaOnnxRecognizer(withDefaultPadding, "en"), halfClip(clip))
        assertEquals(
            "steps for the default padding",
            (clip + SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES) / 160 + 16,
            withDefaultPadding.count("decode"),
        )
        val withShortPadding = ScriptedNative(neverReady = true)
        decodeFailure("short padding", SherpaOnnxRecognizer(withShortPadding, "en", tailPaddingSamples = padded), halfClip(clip))
        assertEquals("steps for a padding of $padded samples", (clip + padded) / 160 + 16, withShortPadding.count("decode"))
    }

    @Test
    fun `the step bound still lets a long clip finish`() {
        val clip = 480_000
        val frames = clip / 160
        val native = ScriptedNative(decodesNeeded = frames / 32 + 1, text = "a long clip")
        val transcript = decodeOk("long clip", SherpaOnnxRecognizer(native, "en"), halfClip(clip))
        assertEquals("the long clip reached the end", "a long clip", transcript.text)
        assertEquals("every step the engine asked for was taken", frames / 32 + 1, native.count("decode"))
    }

    @Test
    fun `the transcript text is trimmed`() {
        val native = ScriptedNative(text = "  hello world \n")
        val transcript = decodeOk("trim", SherpaOnnxRecognizer(native, "en"))
        assertEquals("trimmed text", "hello world", transcript.text)
        assertEquals("the segment carries the trimmed text", "hello world", transcript.segments.single().text)
    }

    @Test
    fun `a blank result gives no segment and a non blank result gives one segment spanning the clip`() {
        for (blank in listOf("", "   ", " \n\t ")) {
            val transcript = decodeOk("blank result", SherpaOnnxRecognizer(ScriptedNative(text = blank), "en"))
            assertEquals("blank result text for '$blank'", "", transcript.text)
            assertTrue("blank result has no segment for '$blank'", transcript.segments.isEmpty())
        }
        val spans = listOf(16_000 to 1_000L, 24_000 to 1_500L, 1_600 to 100L, 1 to 0L)
        for ((samples, endMs) in spans) {
            val transcript = decodeOk("segment span", SherpaOnnxRecognizer(ScriptedNative(text = "word"), "en"), halfClip(samples))
            val segment = transcript.segments.single()
            assertEquals("segment start for $samples samples", 0L, segment.startMs)
            assertEquals("segment end for $samples samples", endMs, segment.endMs)
            assertEquals("segment text for $samples samples", "word", segment.text)
            assertEquals("transcript text for $samples samples", "word", transcript.text)
        }
    }

    @Test
    fun `a one character transcript is kept as text with one segment`() {
        val transcript = decodeOk("one character", SherpaOnnxRecognizer(ScriptedNative(text = "a"), "en"))
        assertEquals("the one character is the text", "a", transcript.text)
        assertEquals("one segment is made", 1, transcript.segments.size)
        assertEquals("the segment carries the one character", "a", transcript.segments.single().text)
    }

    @Test
    fun `a clip longer than two minutes keeps its end time`() {
        val samples = 2_400_000
        val transcript = decodeOk("a clip of 150 seconds", SherpaOnnxRecognizer(ScriptedNative(text = "word"), "en"), halfClip(samples))
        val segment = transcript.segments.single()
        assertEquals("segment start", 0L, segment.startMs)
        assertEquals("segment end of 2400000 samples at 16000 per second", 150_000L, segment.endMs)
    }

    @Test
    fun `the language is the profile language`() {
        for (language in listOf("en", "de")) {
            val spoken = decodeOk("spoken", SherpaOnnxRecognizer(ScriptedNative(text = "word"), language))
            assertEquals("language of a non blank result", language, spoken.language)
            val silent = decodeOk("silent", SherpaOnnxRecognizer(ScriptedNative(text = ""), language))
            assertEquals("language of a blank result", language, silent.language)
        }
    }

    @Test
    fun `a sample rate other than 16000 is refused before any native stream is created`() {
        for (other in listOf(8_000, 44_100, 15_999, 16_001, 0, -16_000)) {
            val native = ScriptedNative()
            val refused = decodeFailure("rate $other", SherpaOnnxRecognizer(native, "en"), rate = other)
            assertTrue("the message for rate $other is not blank", refused.message.orEmpty().isNotBlank())
            assertEquals("no native call for rate $other", emptyList<String>(), native.log)
        }
    }

    @Test
    fun `an empty clip is refused before any native stream is created`() {
        val native = ScriptedNative()
        val refused = decodeFailure("an empty clip", SherpaOnnxRecognizer(native, "en"), pcm = FloatArray(0))
        assertTrue("the message for an empty clip is not blank", refused.message.orEmpty().isNotBlank())
        assertEquals("no native call for an empty clip", emptyList<String>(), native.log)
    }
}
