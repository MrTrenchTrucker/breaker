package dev.breaker.server.whisper.http

import io.ktor.http.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The pure format rules: exact case sensitive parsing, the two subtitle content types, and the
// body built from a stored result. Nothing here touches a route.

internal class JobFormatTest {

    @Test
    fun `a missing format is json`() {
        assertEquals("whisper-server: no format parameter means json", JobFormat.JSON, parseJobFormat(null))
    }

    @Test
    fun `json srt and vtt parse`() {
        assertEquals("whisper-server: json parses", JobFormat.JSON, parseJobFormat("json"))
        assertEquals("whisper-server: srt parses", JobFormat.SRT, parseJobFormat("srt"))
        assertEquals("whisper-server: vtt parses", JobFormat.VTT, parseJobFormat("vtt"))
    }

    @Test
    fun `an unknown format is rejected`() {
        assertNull("whisper-server: a word that is not a format is rejected", parseJobFormat("xml"))
        assertNull("whisper-server: a subtitle extension is rejected", parseJobFormat("srt.txt"))
    }

    @Test
    fun `an empty format and an upper case format are rejected`() {
        assertNull("whisper-server: an empty format is rejected", parseJobFormat(""))
        assertNull("whisper-server: an upper case SRT is rejected", parseJobFormat("SRT"))
        assertNull("whisper-server: a mixed case Srt is rejected", parseJobFormat("Srt"))
        assertNull("whisper-server: a padded srt is rejected", parseJobFormat(" srt"))
    }

    @Test
    fun `the two subtitle content types carry the charset`() {
        val srt = SRT_CONTENT_TYPE.toString()
        assertTrue("whisper-server: srt must be application/x-subrip", srt.startsWith("application/x-subrip"))
        assertTrue("whisper-server: srt must carry charset=utf-8", srt.contains("charset=utf-8"))
        val vtt = VTT_CONTENT_TYPE.toString()
        assertTrue("whisper-server: vtt must be text/vtt", vtt.startsWith("text/vtt"))
        assertTrue("whisper-server: vtt must carry charset=utf-8", vtt.contains("charset=utf-8"))
    }

    @Test
    fun `srt and vtt render the segments of a stored result`() {
        val stored = """{"text":"hi","segments":[{"start":0.0,"end":1.0,"text":"hi"}]}"""
        val srt = renderJobBody(JobFormat.SRT, stored)
        assertEquals(
            "whisper-server: the srt body carries one numbered cue",
            "1\n00:00:00,000 --> 00:00:01,000\nhi\n\n",
            srt?.text,
        )
        assertEquals("whisper-server: the srt body is served as subrip", SRT_CONTENT_TYPE, srt?.contentType)
        val vtt = renderJobBody(JobFormat.VTT, stored)
        assertEquals(
            "whisper-server: the vtt body carries the header and one cue",
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nhi\n\n",
            vtt?.text,
        )
        assertEquals("whisper-server: the vtt body is served as text/vtt", VTT_CONTENT_TYPE, vtt?.contentType)
    }

    @Test
    fun `a done result with no segments renders an empty srt body and the vtt header`() {
        val stored = """{"text":"hi","segments":[]}"""
        assertEquals("whisper-server: no cue is an empty srt body", "", renderJobBody(JobFormat.SRT, stored)?.text)
        assertEquals(
            "whisper-server: no cue is the vtt header and a blank line",
            "WEBVTT\n\n",
            renderJobBody(JobFormat.VTT, stored)?.text,
        )
    }

    @Test
    fun `a stored result that cannot be parsed has no subtitle body`() {
        assertNull("whisper-server: a blank stored result cannot be parsed", renderJobBody(JobFormat.SRT, ""))
        assertNull("whisper-server: a blank text cannot be parsed", renderJobBody(JobFormat.VTT, """{"text":"   "}"""))
        assertNull("whisper-server: an object with no text cannot be parsed", renderJobBody(JobFormat.SRT, "{}"))
    }

    @Test
    fun `the json render is the status and result body`() {
        val body = renderJobBody(JobFormat.JSON, """{"text":"hi"}""")
        assertEquals(
            "whisper-server: the json body is the done status and the stored result",
            """{"status":"done","result":{"text":"hi"}}""",
            body?.text,
        )
        assertEquals(
            "whisper-server: the json body is served as application/json",
            ContentType.Application.Json,
            body?.contentType,
        )
    }
}
