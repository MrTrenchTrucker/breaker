package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The `format` query parameter on the poll route: the default JSON is unchanged, srt and vtt
// answer a subtitle body under their own content type, an unknown value is a 400 that fires
// once the job is known but regardless of its state, and a job that is not done keeps its JSON
// status body for a format it can serve.

internal class TranscriptionFormatRouteTest {

    private val opened = ArrayList<TempDatabase>()

    private fun openStore(): JobStore {
        val temp = TestDatabases.openTemp()
        opened.add(temp)
        return JobFixtures.store(temp, MutableClock(JobFixtures.START))
    }

    @After
    fun closeDatabases() {
        for (temp in opened) {
            temp.close()
        }
    }

    private suspend fun HttpClient.poll(jobId: Long, format: String? = null): HttpResponse {
        val path = if (format == null) "/v1/jobs/$jobId" else "/v1/jobs/$jobId?format=$format"
        return get(path) { header("Authorization", "Bearer test-token") }
    }

    private suspend fun HttpClient.pollNoAuth(query: String): HttpResponse = get("/v1/jobs/1?$query")

    // The header is checked as a value, not against the constant the route serves, so a wrong
    // or missing charset cannot pass.
    private fun assertSubrip(message: String, header: String?) {
        val value = header ?: ""
        assertTrue("$message: the header must start with application/x-subrip", value.startsWith("application/x-subrip"))
        assertTrue("$message: the header must carry charset=utf-8", value.contains("charset=utf-8"))
    }

    private fun assertVtt(message: String, header: String?) {
        val value = header ?: ""
        assertTrue("$message: the header must start with text/vtt", value.startsWith("text/vtt"))
        assertTrue("$message: the header must carry charset=utf-8", value.contains("charset=utf-8"))
    }

    private val doneNoCue = """{"text":"hi","segments":[]}"""
    private val doneCue = """{"text":"hi","segments":[{"start":0.0,"end":1.5,"text":"hi"}],"language":"en"}"""
    private val doneBody = """{"status":"done","result":{"text":"hi","segments":[]}}"""

    @Test
    fun `no format parameter returns the same json body as today`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.poll(id)
            assertEquals("whisper-server: the status is 200", 200, response.status.value)
            assertEquals("whisper-server: the default body is unchanged", doneBody, response.bodyAsText())
            assertEquals("whisper-server: the default body is application/json", "application/json", response.headers[HttpHeaders.ContentType])
        }
    }

    @Test
    fun `format json returns the same json body as today`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.poll(id, format = "json")
            assertEquals("whisper-server: format json answers 200", 200, response.status.value)
            assertEquals("whisper-server: format json keeps the body", doneBody, response.bodyAsText())
            assertEquals("whisper-server: format json keeps the json content type", "application/json", response.headers[HttpHeaders.ContentType])
        }
    }

    @Test
    fun `format srt returns the subrip content type and a numbered cue`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneCue)
            val response = client.poll(id, format = "srt")
            assertEquals("whisper-server: srt answers 200", 200, response.status.value)
            assertSubrip("whisper-server: the srt body is application/x-subrip with utf-8", response.headers[HttpHeaders.ContentType])
            assertEquals("whisper-server: the srt body is one numbered cue", "1\n00:00:00,000 --> 00:00:01,500\nhi\n\n", response.bodyAsText())
        }
    }

    @Test
    fun `format vtt returns the vtt content type and the header`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneCue)
            val response = client.poll(id, format = "vtt")
            assertEquals("whisper-server: vtt answers 200", 200, response.status.value)
            assertVtt("whisper-server: the vtt body is text/vtt with utf-8", response.headers[HttpHeaders.ContentType])
            assertEquals("whisper-server: the vtt body is the header and one cue", "WEBVTT\n\n00:00:00.000 --> 00:00:01.500\nhi\n\n", response.bodyAsText())
        }
    }

    @Test
    fun `an unknown format answers 400 with the unknown format error`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.poll(id, format = "xml")
            assertEquals("whisper-server: an unknown format answers 400", 400, response.status.value)
            assertEquals("whisper-server: the 400 body is the unknown format error", """{"error":"unknown format"}""", response.bodyAsText())
        }
    }

    @Test
    fun `an empty format answers 400 with the unknown format error`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.poll(id, format = "")
            assertEquals("whisper-server: an empty format answers 400", 400, response.status.value)
            assertEquals("whisper-server: the empty format body is the unknown format error", """{"error":"unknown format"}""", response.bodyAsText())
        }
    }

    @Test
    fun `an upper case format answers 400 with the unknown format error`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.poll(id, format = "SRT")
            assertEquals("whisper-server: an upper case format answers 400", 400, response.status.value)
            assertEquals("whisper-server: the upper case body is the unknown format error", """{"error":"unknown format"}""", response.bodyAsText())
        }
    }

    @Test
    fun `a repeated format parameter uses the first value`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.get("/v1/jobs/$id?format=srt&format=vtt") {
                header("Authorization", "Bearer test-token")
            }
            assertEquals("whisper-server: the first value answers 200", 200, response.status.value)
            assertSubrip("whisper-server: the first value is srt", response.headers[HttpHeaders.ContentType])
            assertEquals("whisper-server: the srt body is empty for no cue", "", response.bodyAsText())
        }
    }

    @Test
    fun `srt on a queued job answers the same json status body as today`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = store.enqueue(1L, JobFixtures.audio(1))
            val response = client.poll(id, format = "srt")
            assertEquals("whisper-server: a queued job answers 200", 200, response.status.value)
            assertEquals("whisper-server: a queued job keeps its json status body", """{"status":"queued","result":null}""", response.bodyAsText())
            assertEquals("whisper-server: a queued job keeps the json content type", "application/json", response.headers[HttpHeaders.ContentType])
        }
    }

    @Test
    fun `an unknown format on a queued job answers 400`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = store.enqueue(1L, JobFixtures.audio(1))
            val response = client.poll(id, format = "xml")
            assertEquals("whisper-server: an unknown format on a queued job answers 400", 400, response.status.value)
            assertEquals("whisper-server: the queued 400 body is the unknown format error", """{"error":"unknown format"}""", response.bodyAsText())
        }
    }

    @Test
    fun `srt on a failed job answers the same json body as today`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.failedJob(store, error = "boom")
            val response = client.poll(id, format = "srt")
            assertEquals("whisper-server: a failed job answers 200", 200, response.status.value)
            assertEquals("whisper-server: a failed job keeps its json body and error", """{"status":"failed","result":null,"error":"boom"}""", response.bodyAsText())
        }
    }

    @Test
    fun `srt on an unknown job id is still 404`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val missing = client.poll(999, format = "srt")
            assertEquals("whisper-server: an unknown id answers 404", 404, missing.status.value)
            assertEquals("whisper-server: the 404 body is the unknown or expired error", """{"error":"unknown or expired"}""", missing.bodyAsText())
            val unknownFormat = client.poll(999, format = "xml")
            assertEquals("whisper-server: the job lookup runs before the format check", 404, unknownFormat.status.value)
            assertEquals("whisper-server: the unknown id body is unchanged", """{"error":"unknown or expired"}""", unknownFormat.bodyAsText())
        }
    }

    @Test
    fun `an unknown format with no token is still 401 not 400`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.pollNoAuth("format=SRT")
            assertEquals("whisper-server: auth runs before the format check", 401, response.status.value)
            assertEquals("whisper-server: the refusal body is the unauthorized error", """{"error":"unauthorized"}""", response.bodyAsText())
        }
    }

    @Test
    fun `srt on a done job with no segments answers an empty body`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.poll(id, format = "srt")
            assertEquals("whisper-server: a done job with no cue answers 200", 200, response.status.value)
            assertEquals("whisper-server: the srt body is empty", "", response.bodyAsText())
            assertSubrip("whisper-server: the empty srt body is still subrip", response.headers[HttpHeaders.ContentType])
        }
    }

    @Test
    fun `vtt on a done job with no segments answers the header only`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = doneNoCue)
            val response = client.poll(id, format = "vtt")
            assertEquals("whisper-server: a done job with no cue answers 200", 200, response.status.value)
            assertEquals("whisper-server: the vtt body is the header and a blank line", "WEBVTT\n\n", response.bodyAsText())
            assertVtt("whisper-server: the header body is still text/vtt", response.headers[HttpHeaders.ContentType])
        }
    }

    @Test
    fun `an unparsable stored result answers the json body`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = "{}")
            val response = client.poll(id, format = "srt")
            assertEquals("whisper-server: the fallback answers 200", 200, response.status.value)
            assertEquals("whisper-server: the fallback is the json body this route always answered", """{"status":"done","result":{}}""", response.bodyAsText())
            assertEquals("whisper-server: the fallback keeps the json content type", "application/json", response.headers[HttpHeaders.ContentType])
        }
    }
}
