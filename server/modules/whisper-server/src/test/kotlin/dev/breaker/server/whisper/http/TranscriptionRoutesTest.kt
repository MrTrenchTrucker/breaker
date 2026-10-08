package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStatus
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

internal class TranscriptionRoutesTest {

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

    private suspend fun HttpClient.postAudio(body: MultiPartFormDataContent): HttpResponse =
        post("/v1/audio/transcriptions") { setBody(body) }

    private suspend fun HttpClient.poll(jobId: Long): HttpResponse = get("/v1/jobs/$jobId")

    @Test
    fun `a file and model part are accepted with 202 and a queued job`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.postAudio(multipartBody(file = JobFixtures.audio(1), model = "m"))
            assertEquals(202, response.status.value)
            assertEquals("{\"job_id\":1,\"status\":\"queued\"}", response.bodyAsText())
            assertEquals(JobStatus.QUEUED, store.get(1)?.status)
        }
    }

    @Test
    fun `an enqueued job is stored under the owner account`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.postAudio(multipartBody(file = JobFixtures.audio(1), model = "m"))
            assertEquals(202, response.status.value)
            val job = store.get(1)
            assertEquals(OWNER_ACCOUNT_ID, job?.ownerAccountId)
        }
    }

    @Test
    fun `a file and model and language part are accepted with 202`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.postAudio(
                multipartBody(file = JobFixtures.audio(1), model = "m", language = "en"),
            )
            assertEquals(202, response.status.value)
        }
    }

    @Test
    fun `a request without a file part is rejected with 400`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.postAudio(multipartBody(file = null, model = "m"))
            assertEquals(400, response.status.value)
            assertEquals("{\"error\":\"the file part is required\"}", response.bodyAsText())
        }
    }

    @Test
    fun `a request with an empty file part is rejected with 400`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.postAudio(multipartBody(file = ByteArray(0), model = "m"))
            assertEquals(400, response.status.value)
            assertEquals("{\"error\":\"the file part is required\"}", response.bodyAsText())
        }
    }

    @Test
    fun `a request without a model part is rejected with 400`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.postAudio(multipartBody(file = JobFixtures.audio(1), model = null))
            assertEquals(400, response.status.value)
            assertEquals("{\"error\":\"the model part is required\"}", response.bodyAsText())
        }
    }

    @Test
    fun `a request with a blank model part is rejected with 400`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.postAudio(multipartBody(file = JobFixtures.audio(1), model = "   "))
            assertEquals(400, response.status.value)
            assertEquals("{\"error\":\"the model part is required\"}", response.bodyAsText())
        }
    }

    @Test
    fun `a file exactly at the cap is accepted`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val atCap = ByteArray(MAX_AUDIO_BYTES.toInt())
            val response = client.postAudio(multipartBody(file = atCap, model = "m"))
            assertEquals(202, response.status.value)
        }
    }

    @Test
    fun `a file one byte over the cap is rejected with 413`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val overCap = ByteArray((MAX_AUDIO_BYTES + 1).toInt())
            val response = client.postAudio(multipartBody(file = overCap, model = "m"))
            assertEquals(413, response.status.value)
            assertEquals("{\"error\":\"the audio is larger than the limit\"}", response.bodyAsText())
        }
    }

    @Test
    fun `enqueuing wakes the worker`() {
        val store = openStore()
        val wakes = AtomicInteger(0)
        withTranscriptionApi(store, wake = { wakes.incrementAndGet() }) { client ->
            assertEquals(0, wakes.get())
            val response = client.postAudio(multipartBody(file = JobFixtures.audio(1), model = "m"))
            assertEquals(202, response.status.value)
            assertEquals(1, wakes.get())
        }
    }

    @Test
    fun `a queued job reports queued with a null result`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = store.enqueue(1L, JobFixtures.audio(1))
            val response = client.poll(id)
            assertEquals(200, response.status.value)
            assertEquals("{\"status\":\"queued\",\"result\":null}", response.bodyAsText())
        }
    }

    @Test
    fun `a processing job reports processing with a null result`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = store.enqueue(1L, JobFixtures.audio(1))
            store.claimNext()
            val response = client.poll(id)
            assertEquals(200, response.status.value)
            assertEquals("{\"status\":\"processing\",\"result\":null}", response.bodyAsText())
        }
    }

    @Test
    fun `a done job reports its result object`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = "{\"text\":\"hi\"}")
            val response = client.poll(id)
            assertEquals(200, response.status.value)
            assertEquals("{\"status\":\"done\",\"result\":{\"text\":\"hi\"}}", response.bodyAsText())
        }
    }

    @Test
    fun `a done job is gone on the second read`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.doneJob(store, result = "{\"text\":\"hi\"}")
            assertEquals("{\"status\":\"done\",\"result\":{\"text\":\"hi\"}}", client.poll(id).bodyAsText())
            assertEquals("{\"status\":\"done\",\"result\":null}", client.poll(id).bodyAsText())
        }
    }

    @Test
    fun `a failed job reports failed with a null result and the error`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val id = JobFixtures.failedJob(store, error = "boom")
            val response = client.poll(id)
            assertEquals(200, response.status.value)
            assertEquals("{\"status\":\"failed\",\"result\":null,\"error\":\"boom\"}", response.bodyAsText())
        }
    }

    @Test
    fun `an unknown job id is not found`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.poll(999)
            assertEquals(404, response.status.value)
            assertEquals("{\"error\":\"no such job\"}", response.bodyAsText())
        }
    }

    @Test
    fun `a non numeric job id is not found`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.get("/v1/jobs/abc")
            assertEquals(404, response.status.value)
            assertEquals("{\"error\":\"no such job\"}", response.bodyAsText())
        }
    }

    @Test
    fun `the status mapping covers every job status`() {
        assertEquals("{\"status\":\"queued\",\"result\":null}", pollSingle("queued"))
        assertEquals("{\"status\":\"processing\",\"result\":null}", pollSingle("processing"))
        assertEquals("{\"status\":\"done\",\"result\":{\"text\":\"x\"}}", pollSingle("done"))
        assertEquals("{\"status\":\"failed\",\"result\":null,\"error\":\"e\"}", pollSingle("failed"))
    }

    /** Runs one job to the named status in its own store and returns the poll body. */
    private fun pollSingle(status: String): String {
        val store = openStore()
        var body = ""
        withTranscriptionApi(store) { client ->
            val id = when (status) {
                "queued" -> store.enqueue(1L, JobFixtures.audio(1))
                "processing" -> {
                    val queued = store.enqueue(1L, JobFixtures.audio(1))
                    store.claimNext()
                    queued
                }
                "done" -> JobFixtures.doneJob(store, result = "{\"text\":\"x\"}")
                else -> JobFixtures.failedJob(store, error = "e")
            }
            body = client.poll(id).bodyAsText()
        }
        return body
    }
}
