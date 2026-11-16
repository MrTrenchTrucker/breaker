package dev.breaker.server.whisper.subtitle

import dev.breaker.server.whisper.worker.TranscriptionSegment
import org.junit.Assert.assertEquals
import org.junit.Test

// VTT: the WEBVTT header and a blank line, no cue index, a dot before the millis, three escapes.

internal class VttWriterTest {

    private fun segment(start: Double, end: Double, text: String) = TranscriptionSegment(start, end, text)

    @Test
    fun `vtt starts with the header and a blank line`() {
        val body = renderVtt(listOf(segment(0.0, 1.0, "hi")))
        assertEquals(
            "subtitle: vtt opens with WEBVTT and a blank line and carries no cue index",
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nhi\n\n",
            body,
        )
    }

    @Test
    fun `vtt escapes ampersand and angle brackets`() {
        val body = renderVtt(listOf(segment(0.0, 1.0, "a & b < c > d")))
        assertEquals(
            "subtitle: vtt escapes the three markup characters",
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\na &amp; b &lt; c &gt; d\n\n",
            body,
        )
    }

    @Test
    fun `vtt renders the dot millisecond separator`() {
        val body = renderVtt(listOf(segment(0.0, 1.5, "hi")))
        assertEquals(
            "subtitle: vtt writes the dot separator and the millis",
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.500\nhi\n\n",
            body,
        )
    }

    @Test
    fun `vtt with no cue is the header and a blank line only`() {
        assertEquals("subtitle: no segments is the header and a blank line", "WEBVTT\n\n", renderVtt(emptyList()))
        assertEquals(
            "subtitle: only blank segments is the header and a blank line",
            "WEBVTT\n\n",
            renderVtt(listOf(segment(0.0, 1.0, "  \n "))),
        )
    }

    @Test
    fun `vtt ends with exactly one trailing newline`() {
        val body = renderVtt(listOf(segment(0.0, 1.0, "one"), segment(1.0, 2.0, "two")))
        assertEquals(
            "subtitle: the last cue ends with one blank line and nothing more",
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\none\n\n00:00:01.000 --> 00:00:02.000\ntwo\n\n",
            body,
        )
    }

    @Test
    fun `vtt clamps a negative start and an end before the start`() {
        val body = renderVtt(listOf(segment(-0.5, 1.0, "a"), segment(2.0, 1.0, "b")))
        assertEquals(
            "subtitle: vtt writes the clamped times",
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\na\n\n00:00:02.000 --> 00:00:02.000\nb\n\n",
            body,
        )
    }
}
