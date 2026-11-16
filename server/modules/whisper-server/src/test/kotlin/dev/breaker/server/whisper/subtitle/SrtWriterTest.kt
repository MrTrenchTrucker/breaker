package dev.breaker.server.whisper.subtitle

import dev.breaker.server.whisper.worker.TranscriptionSegment
import org.junit.Assert.assertEquals
import org.junit.Test

// SRT: one numbered cue per non-empty segment, a comma before the millis, text unescaped.

internal class SrtWriterTest {

    private fun segment(start: Double, end: Double, text: String) = TranscriptionSegment(start, end, text)

    @Test
    fun `srt numbers cues from one and skips an empty segment without a gap`() {
        val body = renderSrt(
            listOf(
                segment(0.0, 1.0, "one"),
                segment(1.0, 2.0, "   "),
                segment(2.0, 3.0, "three"),
            ),
        )
        assertEquals(
            "subtitle: the blank segment is skipped and the next cue is numbered two",
            "1\n00:00:00,000 --> 00:00:01,000\none\n\n2\n00:00:02,000 --> 00:00:03,000\nthree\n\n",
            body,
        )
    }

    @Test
    fun `srt renders the comma millisecond separator`() {
        val body = renderSrt(listOf(segment(0.0, 1.5, "hi")))
        assertEquals(
            "subtitle: srt writes the comma separator and the millis",
            "1\n00:00:00,000 --> 00:00:01,500\nhi\n\n",
            body,
        )
    }

    @Test
    fun `srt does not escape ampersand or angle brackets`() {
        val body = renderSrt(listOf(segment(0.0, 1.0, "a & b < c > d")))
        assertEquals(
            "subtitle: srt text is written verbatim",
            "1\n00:00:00,000 --> 00:00:01,000\na & b < c > d\n\n",
            body,
        )
    }

    @Test
    fun `srt with no cue is an empty body`() {
        assertEquals("subtitle: no segments is the empty string", "", renderSrt(emptyList()))
        assertEquals(
            "subtitle: only blank segments is the empty string",
            "",
            renderSrt(listOf(segment(0.0, 1.0, " \n\t "))),
        )
    }

    @Test
    fun `srt clamps a negative start and an end before the start`() {
        val body = renderSrt(listOf(segment(-0.5, 1.0, "a"), segment(2.0, 1.0, "b")))
        assertEquals(
            "subtitle: srt writes the clamped times",
            "1\n00:00:00,000 --> 00:00:01,000\na\n\n2\n00:00:02,000 --> 00:00:02,000\nb\n\n",
            body,
        )
    }
}
