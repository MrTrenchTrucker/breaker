package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: the transcription front requires a live session token. The token comes from the
 * Authorization header (Bearer <token>); the check fails closed, so a missing, not-live or
 * unreachable check is one plain 401. The enqueue moves the token's expiry (use); the poll
 * uses the read-only check (peek). A job is owned by the account the token resolves to, so a
 * job id of another account answers the same 404 as a missing id.
 */
internal class TranscriptionAuthTest {

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

    private suspend fun HttpClient.postBearer(path: String, body: MultiPartFormDataContent, token: String): HttpResponse =
        post(path) {
            header("Authorization", "Bearer $token")
            setBody(body)
        }

    // ---- enqueue auth ----

    @Test
    fun `enqueue without a token is refused with 401`() {
        val store = openStore()
        val check = FakeTokenCheck()
        withTranscriptionApi(store, tokenCheck = check) { client ->
            val response = client.post("/v1/audio/transcriptions") {
                setBody(multipartBody(file = JobFixtures.audio(1), model = "m"))
            }
            assertEquals(401, response.status.value)
            assertEquals("""{"error":"unauthorized"}""", response.bodyAsText())
            assertTrue("nothing is enqueued when the token is missing", store.get(1) == null)
        }
    }

    @Test
    fun `enqueue with a not-live token is refused with 401`() {
        val store = openStore()
        val check = FakeTokenCheck { TokenAnswer.NotLive }
        withTranscriptionApi(store, tokenCheck = check) { client ->
            val response = client.postBearer("/v1/audio/transcriptions", multipartBody(file = JobFixtures.audio(1), model = "m"), "dead-token")
            assertEquals(401, response.status.value)
            assertEquals("""{"error":"unauthorized"}""", response.bodyAsText())
        }
    }

    @Test
    fun `enqueue with an unavailable check is refused with 401`() {
        val store = openStore()
        val check = FakeTokenCheck { TokenAnswer.Unavailable }
        withTranscriptionApi(store, tokenCheck = check) { client ->
            val response = client.postBearer("/v1/audio/transcriptions", multipartBody(file = JobFixtures.audio(1), model = "m"), "any-token")
            assertEquals(401, response.status.value)
            assertEquals("""{"error":"unauthorized"}""", response.bodyAsText())
        }
    }

    @Test
    fun `a not-live token and an unavailable check give the same 401 body`() {
        val store = openStore()
        var notLiveBody = ""
        var unavailableBody = ""
        withTranscriptionApi(store, tokenCheck = FakeTokenCheck { TokenAnswer.NotLive }) { client ->
            notLiveBody = client.postBearer("/v1/audio/transcriptions", multipartBody(file = JobFixtures.audio(1), model = "m"), "dead").bodyAsText()
        }
        withTranscriptionApi(store, tokenCheck = FakeTokenCheck { TokenAnswer.Unavailable }) { client ->
            unavailableBody = client.postBearer("/v1/audio/transcriptions", multipartBody(file = JobFixtures.audio(1), model = "m"), "any").bodyAsText()
        }
        assertEquals("one plain 401 body for every auth failure", notLiveBody, unavailableBody)
        assertEquals("""{"error":"unauthorized"}""", notLiveBody)
    }

    // ---- poll auth ----

    @Test
    fun `the bearer scheme is case-insensitive but a foreign scheme is refused`() {
        val store = openStore()
        // A lowercase "bearer" is the same scheme (RFC 9110), so it is accepted.
        withTranscriptionApi(store, tokenCheck = FakeTokenCheck { TokenAnswer.Live(1L) }) { client ->
            val response = client.post("/v1/audio/transcriptions") {
                header("Authorization", "bearer token-a")
                setBody(multipartBody(file = JobFixtures.audio(1), model = "m"))
            }
            assertEquals(202, response.status.value)
        }
        // A foreign scheme is not Bearer, so it is refused with the same plain 401.
        withTranscriptionApi(store) { client ->
            val response = client.post("/v1/audio/transcriptions") {
                header("Authorization", "Basic token-a")
                setBody(multipartBody(file = JobFixtures.audio(1), model = "m"))
            }
            assertEquals(401, response.status.value)
            assertEquals("""{"error":"unauthorized"}""", response.bodyAsText())
        }
    }

    @Test
    fun `poll without a token is refused with 401`() {
        val store = openStore()
        withTranscriptionApi(store) { client ->
            val response = client.get("/v1/jobs/1")
            assertEquals(401, response.status.value)
            assertEquals("""{"error":"unauthorized"}""", response.bodyAsText())
        }
    }

    @Test
    fun `poll with a not-live token is refused with 401`() {
        val store = openStore()
        withTranscriptionApi(store, tokenCheck = FakeTokenCheck { TokenAnswer.NotLive }) { client ->
            val response = client.get("/v1/jobs/1") { header("Authorization", "Bearer dead") }
            assertEquals(401, response.status.value)
            assertEquals("""{"error":"unauthorized"}""", response.bodyAsText())
        }
    }

    @Test
    fun `poll with an unavailable check is refused with 401`() {
        val store = openStore()
        withTranscriptionApi(store, tokenCheck = FakeTokenCheck { TokenAnswer.Unavailable }) { client ->
            val response = client.get("/v1/jobs/1") { header("Authorization", "Bearer any") }
            assertEquals(401, response.status.value)
            assertEquals("""{"error":"unauthorized"}""", response.bodyAsText())
        }
    }

    // ---- account ownership ----

    @Test
    fun `a live token enqueues under its own account`() {
        val store = openStore()
        val check = FakeTokenCheck { TokenAnswer.Live(42L) }
        withTranscriptionApi(store, tokenCheck = check) { client ->
            val response = client.postBearer("/v1/audio/transcriptions", multipartBody(file = JobFixtures.audio(1), model = "m"), "token-a")
            assertEquals(202, response.status.value)
            assertEquals(42L, store.get(1)?.ownerAccountId)
        }
    }

    @Test
    fun `account B cannot read account A job - same 404 as a missing id`() {
        val store = openStore()
        // Token A owns account 1; token B owns account 2.
        withTranscriptionApi(store, tokenCheck = FakeTokenCheck { TokenAnswer.Live(1L) }) { client ->
            val response = client.postBearer("/v1/audio/transcriptions", multipartBody(file = JobFixtures.audio(1), model = "m"), "token-a")
            assertEquals(202, response.status.value)
        }
        var foreignBody = ""
        var missingBody = ""
        withTranscriptionApi(store, tokenCheck = FakeTokenCheck { TokenAnswer.Live(2L) }) { client ->
            foreignBody = client.get("/v1/jobs/1") { header("Authorization", "Bearer token-b") }.bodyAsText()
            missingBody = client.get("/v1/jobs/999") { header("Authorization", "Bearer token-b") }.bodyAsText()
        }
        assertEquals("a foreign account's job id answers like a missing id", missingBody, foreignBody)
        assertEquals("""{"error":"unknown or expired"}""", foreignBody)
    }

    @Test
    fun `a live token still enqueues and polls its own job - regression`() {
        val store = openStore()
        val check = FakeTokenCheck { TokenAnswer.Live(1L) }
        withTranscriptionApi(store, tokenCheck = check) { client ->
            val enqueued = client.postBearer("/v1/audio/transcriptions", multipartBody(file = JobFixtures.audio(1), model = "m"), "token-a")
            assertEquals(202, enqueued.status.value)
            val polled = client.get("/v1/jobs/1") { header("Authorization", "Bearer token-a") }
            assertEquals(200, polled.status.value)
            assertEquals("""{"status":"queued","result":null}""", polled.bodyAsText())
        }
        assertEquals("the enqueue calls use, the poll calls peek", listOf("use", "peek"), check.ops)
    }
}
