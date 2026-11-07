package dev.breaker.server.whisper

import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.http.AgentRateLimit
import dev.breaker.server.whisper.http.FakeTokenCheck
import dev.breaker.server.whisper.http.TokenAnswer
import dev.breaker.server.whisper.http.TranscriptionApi
import dev.breaker.server.whisper.http.withTranscriptionApi
import dev.breaker.server.whisper.http.multipartBody
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveMultipart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.testing.testApplication
import io.ktor.http.content.MultiPartData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route-level tests for the optional per-agent-token rate limit. Uses the real
 *  TranscriptionApi with a real AgentRateLimit wired in (off when null). */
internal class AgentLimitRouteTest {

    private val opened = ArrayList<AutoCloseable>()

    private fun openStore(): JobStore {
        val temp = TestDatabases.openTemp()
        opened.add(temp)
        return JobFixtures.store(temp, MutableClock(JobFixtures.START))
    }

    private fun closeAll() {
        for (c in opened) c.close()
    }

    private suspend fun HttpClient.postBearer(
        path: String,
        body: MultiPartFormDataContent,
        token: String,
    ): HttpResponse =
        post(path) {
            header("Authorization", "Bearer $token")
            setBody(body)
        }

    private fun newBody(): MultiPartFormDataContent = multipartBody(file = JobFixtures.audio(1), model = "m")

    private fun agentApi(
        store: JobStore,
        cap: Int,
        kind: String = "agent",
        readMultipart: (suspend ApplicationCall.() -> MultiPartData)? = null,
    ): TranscriptionApi {
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(1L, kind) }
        val limit = AgentRateLimit(cap, MutableClock(JobFixtures.START))
        return if (readMultipart == null) {
            TranscriptionApi(store, {}, tokenCheck = tokenCheck, limit = limit)
        } else {
            TranscriptionApi(store, {}, readMultipart = readMultipart, tokenCheck = tokenCheck, limit = limit)
        }
    }

    @Test
    fun `with no rate limit set many requests from one agent token all pass`() {
        val store = openStore()
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(1L, "agent") }
        var runs = 0
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = tokenCheck, limit = null).install(this) }
            repeat(200) {
                val response = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
                assertEquals(202, response.status.value)
                runs++
            }
        }
        closeAll()
        assertEquals(200, runs)
    }

    @Test
    fun `a session token is never limited even over the cap`() {
        val store = openStore()
        val api = agentApi(store, 1, kind = "session")
        var runs = 0
        testApplication {
            application { api.install(this) }
            repeat(20) {
                val response = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
                assertEquals(202, response.status.value)
                runs++
            }
        }
        closeAll()
        assertEquals(20, runs)
    }

    @Test
    fun `over the cap a post answers 429 with the retry after and does not read the upload`() {
        val store = openStore()
        var uploadRead = false
        val readMultipart: suspend ApplicationCall.() -> MultiPartData = {
            uploadRead = true
            receiveMultipart()
        }
        val api = agentApi(store, 1, readMultipart = readMultipart)
        testApplication {
            application { api.install(this) }
            val first = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
            assertEquals(202, first.status.value)
            uploadRead = false
            val second = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
            assertEquals(429, second.status.value)
            assertEquals("the whole minute is left", "60", second.headers["Retry-After"])
            assertTrue("the upload is not read when the request is refused", !uploadRead)
        }
        closeAll()
    }

    @Test
    fun `a not live token is 401 every time and is never counted`() {
        val store = openStore()
        val tokenCheck = FakeTokenCheck { TokenAnswer.NotLive }
        val api = TranscriptionApi(
            store,
            {},
            tokenCheck = tokenCheck,
            limit = AgentRateLimit(1, MutableClock(JobFixtures.START)),
        )
        testApplication {
            application { api.install(this) }
            val first = client.postBearer("/v1/audio/transcriptions", newBody(), "dead-token")
            assertEquals(401, first.status.value)
            val second = client.postBearer("/v1/audio/transcriptions", newBody(), "dead-token")
            assertEquals("a dead token stays 401, it is never turned into a 429", 401, second.status.value)
            val poll = client.get("/v1/jobs/1") { header("Authorization", "Bearer dead-token") }
            assertEquals(401, poll.status.value)
        }
        closeAll()
    }

    @Test
    fun `post and get with the same agent token share one counter`() {
        val store = openStore()
        val api = agentApi(store, 2)
        testApplication {
            application { api.install(this) }
            val enqueued = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
            assertEquals(202, enqueued.status.value)
            val polled = client.get("/v1/jobs/1") { header("Authorization", "Bearer tok-a") }
            assertEquals("the second request of the minute is allowed", 200, polled.status.value)
            val again = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
            assertEquals("the third request is over the cap of two", 429, again.status.value)
            val againPoll = client.get("/v1/jobs/1") { header("Authorization", "Bearer tok-a") }
            assertEquals("the poll shares the counter, so it is refused too", 429, againPoll.status.value)
            assertEquals("the whole minute is left", "60", againPoll.headers["Retry-After"])
        }
        closeAll()
    }

    @Test
    fun `a limited poll answers 429 even for a job id that does not exist`() {
        val store = openStore()
        val api = agentApi(store, 1)
        testApplication {
            application { api.install(this) }
            val first = client.get("/v1/jobs/999") { header("Authorization", "Bearer tok-a") }
            assertEquals("an unknown job is 404 while the token is under the cap", 404, first.status.value)
            val second = client.get("/v1/jobs/999") { header("Authorization", "Bearer tok-a") }
            assertEquals("the limit comes before the job lookup", 429, second.status.value)
            assertTrue("the answer carries Retry-After", second.headers.contains("Retry-After"))
        }
        closeAll()
    }

    @Test
    fun `a session token polling over the cap is never answered 429`() {
        val store = openStore()
        val api = agentApi(store, 1, kind = "session")
        testApplication {
            application { api.install(this) }
            repeat(10) {
                val poll = client.get("/v1/jobs/999") { header("Authorization", "Bearer tok-s") }
                assertEquals("a session token is never limited, an unknown job is 404", 404, poll.status.value)
            }
        }
        closeAll()
    }
}
