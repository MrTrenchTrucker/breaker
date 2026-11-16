package dev.breaker.server.whisper.subtitle

import dev.breaker.server.whisper.worker.TranscriptionSegment

// One SRT cue per non-empty segment, numbered from one. The text is NOT escaped: SRT has no
// escape for & or <, and escaping it would show the entity names to the listener.

/** Renders [segments] as an SRT body; a job with no cue renders as the empty string. */
internal fun renderSrt(segments: List<TranscriptionSegment>): String {
    val out = StringBuilder()
    var index = 0
    for (segment in segments) {
        val text = collapseToOneLine(segment.text)
        if (text.isEmpty()) continue
        index += 1
        val (start, end) = clampTimes(segment.start, segment.end)
        out.append(index).append('\n')
        out.append(renderTime(millisOf(start), ','))
            .append(" --> ")
            .append(renderTime(millisOf(end), ','))
            .append('\n')
        out.append(text).append('\n')
        out.append('\n')
    }
    return out.toString()
}
