package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.subtitle.renderSrt
import dev.breaker.server.whisper.subtitle.renderVtt
import dev.breaker.server.whisper.worker.TranscriptionResponse
import io.ktor.http.ContentType

// The `format` query parameter of GET /v1/jobs/{job_id}. Parsing is exact and case sensitive:
// json, srt and vtt are the only known values, and a missing parameter means json. Any other
// value, an empty one included, is rejected by the caller with 400.

/** The output shapes the poll route can answer with. */
internal enum class JobFormat {
    JSON,
    SRT,
    VTT,
}

/** The raw `format` value as a [JobFormat], or null when the value is not one this route serves. */
internal fun parseJobFormat(raw: String?): JobFormat? = when (raw) {
    null, "json" -> JobFormat.JSON
    "srt" -> JobFormat.SRT
    "vtt" -> JobFormat.VTT
    else -> null
}

/** The SRT body type, with the charset the body is written in. */
internal val SRT_CONTENT_TYPE: ContentType = ContentType.parse("application/x-subrip; charset=utf-8")

/** The VTT body type, with the charset the body is written in. */
internal val VTT_CONTENT_TYPE: ContentType = ContentType.parse("text/vtt; charset=utf-8")

/** The status and result body a done answer carries when no subtitle was asked for. */
internal fun doneResultBody(storedResult: String): String = "{\"status\":\"done\",\"result\":$storedResult}"

/** A rendered body and the content type it is served under. */
internal data class RenderedJobBody(val text: String, val contentType: ContentType)

/**
 * Renders the stored result of a done job for [format]. A subtitle body needs the segments, so
 * an unparsable stored result answers null and the caller falls back to the JSON body it always
 * answered. That cannot happen for a result this module wrote: the forwarder always stores a
 * non-blank text and the parser reads it back.
 */
internal fun renderJobBody(format: JobFormat, storedResult: String): RenderedJobBody? {
    if (format == JobFormat.JSON) {
        return RenderedJobBody(doneResultBody(storedResult), ContentType.Application.Json)
    }
    val segments = TranscriptionResponse.parse(storedResult)?.segments ?: return null
    return when (format) {
        JobFormat.SRT -> RenderedJobBody(renderSrt(segments), SRT_CONTENT_TYPE)
        JobFormat.VTT -> RenderedJobBody(renderVtt(segments), VTT_CONTENT_TYPE)
        JobFormat.JSON -> null
    }
}
