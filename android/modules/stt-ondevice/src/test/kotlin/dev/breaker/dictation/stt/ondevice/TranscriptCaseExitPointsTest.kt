package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The case normalisation at the two places text leaves the recognizer: the
 * one-shot transcript (its text and its segment) and every live word-stream
 * update.
 */
class TranscriptCaseExitPointsTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    @Test
    fun `the one-shot transcript text is normalised`() {
        val native = ScriptedNative(decodesNeeded = 1, text = "ALL CAPS SENTENCE FROM THE ENGINE I'M SURE")
        val transcript = decodeOk("an all-caps one-shot decode",
            SherpaOnnxRecognizer(native, "en", tailPaddingSamples = 320))
        assertEquals("the one-shot text is lower-cased with the standalone I restored",
            "all caps sentence from the engine I'm sure", transcript.text)
    }

    @Test
    fun `the one-shot segment text is normalised too`() {
        val native = ScriptedNative(decodesNeeded = 1, text = "THE SEGMENT TEXT IS CAPITAL")
        val transcript = decodeOk("an all-caps one-shot with a segment",
            SherpaOnnxRecognizer(native, "en", tailPaddingSamples = 320))
        assertEquals("the segment carries the same normalised text as the transcript",
            "the segment text is capital", transcript.segments.single().text)
    }

    @Test
    fun `a mixed-case one-shot transcript is left as is`() {
        val native = ScriptedNative(decodesNeeded = 1, text = "A model that cases its own text")
        val transcript = decodeOk("a cased one-shot decode",
            SherpaOnnxRecognizer(native, "en", tailPaddingSamples = 320))
        assertEquals("a transcript with lowercase is left exactly as it is",
            "A model that cases its own text", transcript.text)
    }

    @Test
    fun `every live word-stream update is normalised, not just the final`() {
        val fake = FakeTimedNative(
            listOf(
                timedResult(listOf("${TIMED_MARK}HELLO"), listOf(0.0f)),
                timedResult(listOf("${TIMED_MARK}HELLO", "${TIMED_MARK}WORLD"), listOf(0.0f, 0.5f)),
            ),
        )
        val sink = UpdateSink(2)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        repeat(2) { stream.feed(timedChunk()) }
        sink.awaitExpected()
        stream.stop()
        assertEquals("each update and the final update carry normalised words",
            listOf(
                listOf(HeardWord("hello", 0L)) to false,
                listOf(HeardWord("hello", 0L), HeardWord("world", 500L)) to false,
                listOf(HeardWord("hello", 0L), HeardWord("world", 500L)) to true,
            ),
            sink.updates.map { it.words to it.final },
        )
    }

    @Test
    fun `a live update that mixes cases keeps its uppercase word`() {
        val fake = FakeTimedNative(
            listOf(
                timedResult(listOf("${TIMED_MARK}hello", "${TIMED_MARK}NASA", "${TIMED_MARK}world"),
                    listOf(0.0f, 0.2f, 0.4f)),
            ),
        )
        val sink = UpdateSink(1)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        assertEquals("an update with a lowercase word is left exactly as is, acronym included",
            listOf(
                listOf(HeardWord("hello", 0L), HeardWord("NASA", 200L), HeardWord("world", 400L)) to false,
                listOf(HeardWord("hello", 0L), HeardWord("NASA", 200L), HeardWord("world", 400L)) to true,
            ),
            sink.updates.map { it.words to it.final },
        )
    }
}
