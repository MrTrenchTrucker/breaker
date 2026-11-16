package dev.breaker.server.whisper.subtitle

import dev.breaker.server.whisper.worker.TranscriptionSegment

// The WEBVTT header and a blank line, then one cue per non-empty segment with no index line.
// The text escapes & < > because a VTT cue is parsed as markup.

/** Renders [segments] as a VTT body; a job with no cue renders as the header and a blank line. */
internal fun renderVtt(segments: List<TranscriptionSegment>): String {
    val out = StringBuilder()
    out.append("WEBVTT").append('\n')
    out.append('\n')
    for (segment in segments) {
        val plain = collapseToOneLine(segment.text)
        if (plain.isEmpty()) continue
        val text = escapeVtt(plain)
        val (start, end) = clampTimes(segment.start, segment.end)
        out.append(renderTime(millisOf(start), '.'))
            .append(" --> ")
            .append(renderTime(millisOf(end), '.'))
            .append('\n')
        out.append(text).append('\n')
        out.append('\n')
    }
    return out.toString()
}

private fun escapeVtt(text: String): String {
    val out = StringBuilder()
    for (c in text) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            else -> out.append(c)
        }
    }
    return out.toString()
}
