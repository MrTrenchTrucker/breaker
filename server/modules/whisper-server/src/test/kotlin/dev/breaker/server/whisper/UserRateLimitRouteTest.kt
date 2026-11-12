package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.FakeTokenCheck
import dev.breaker.server.whisper.http.TokenAnswer
import dev.breaker.server.whisper.http.TranscriptionApi
import dev.breaker.server.whisper.http.UserRateLimit
import dev.breaker.server.whisper.http.UserLimitResult
import dev.breaker.server.whisper.http.multipartBody
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import dev.breaker.server.whisper.config.WhisperConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.testing.testApplication
import io.ktor.http.content.MultiPartData
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route-level tests for the per-account rate limit (T18). Applies to session and agent
 *  tokens alike: key is accountId, in-memory fixed window, off when the cap is absent,
 *  429 before the upload is read. Uses the real TranscriptionApi with a real UserRateLimit
 *  wired in. Mirrors AgentLimitRouteTest. */
internal class UserRateLimitRouteTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val opened = java.util.ArrayList<AutoCloseable>()

    private fun openStore(): JobStore {
        val temp = dev.breaker.server.whisper.db.TestDatabases.openTemp()
        opened.add(temp)
        return dev.breaker.server.whisper.jobs.JobFixtures.store(temp, MutableClock(JobFixtures.START))
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

    private suspend fun HttpClient.getBearer(
        path: String,
        token: String,
    ): HttpResponse =
        get(path) {
            header("Authorization", "Bearer $token")
        }

    private fun newBody(): MultiPartFormDataContent = multipartBody(file = JobFixtures.audio(1), model = "m")

    private fun api(store: JobStore, cap: Int): TranscriptionApi {
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, "session") }
        val limit = UserRateLimit(cap, MutableClock(start))
        return TranscriptionApi(store, {}, tokenCheck = tokenCheck, userLimit = limit)
    }

    @Test
    fun `rate limit absent means the limit is off (all requests pass)`() {
        val store = openStore()
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, "session") }
        var runs = 0
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = tokenCheck, userLimit = null).install(this) }
            repeat(200) {
                val response = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
                assertEquals(202, response.status.value)
                runs++
            }
        }
        closeAll()
        assertEquals(200, runs)
    }

    @Test
    fun `up to cap requests from one account in the window are allowed then the next is refused`() {
        val store = openStore()
        val limit = UserRateLimit(2, MutableClock(start))
        var runs = 0
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, "session") }, userLimit = limit).install(this) }
            repeat(2) {
                val response = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
                assertEquals(202, response.status.value)
                runs++
            }
            val third = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(429, third.status.value)
        }
        closeAll()
        assertEquals(2, runs)
    }

    @Test
    fun `another account is not affected by this account hitting the cap`() {
        val store = openStore()
        val limit = UserRateLimit(1, MutableClock(start))
        var account = 7L
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(account, "session") }
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = tokenCheck, userLimit = limit).install(this) }
            val first7 = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(202, first7.status.value)
            val second7 = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(429, second7.status.value)
            account = 8L
            val first8 = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals("another account under its own cap is allowed", 202, first8.status.value)
        }
        closeAll()
    }

    @Test
    fun `session and agent tokens for the same account share one counter`() {
        val store = openStore()
        val limit = UserRateLimit(1, MutableClock(start))
        var kind = "session"
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, kind) }
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = tokenCheck, userLimit = limit).install(this) }
            val first = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(202, first.status.value)
            kind = "agent"
            val second = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
            assertEquals("the same account through an agent token shares the counter", 429, second.status.value)
        }
        closeAll()
    }

    @Test
    fun `a request refused by the agent token limit does not use an account slot`() {
        val store = openStore()
        val accountLimit = UserRateLimit(2, MutableClock(start))
        val agentLimit = dev.breaker.server.whisper.http.AgentRateLimit(1, MutableClock(start))
        var kind = "agent"
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, kind) }
        testApplication {
            application {
                TranscriptionApi(store, {}, tokenCheck = tokenCheck, limit = agentLimit, userLimit = accountLimit).install(this)
            }
            val firstAgent = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
            assertEquals("the first agent request uses account slot 1", 202, firstAgent.status.value)
            val secondAgent = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-a")
            assertEquals("the agent token limit refuses the second agent request", 429, secondAgent.status.value)
            kind = "session"
            val session = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals("the refused request used no slot, so the session request takes slot 2", 202, session.status.value)
            val over = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals("the account is now at its cap of 2", 429, over.status.value)
        }
        closeAll()
    }

    @Test
    fun `the window reopens after 60 s and Retry-After is 30 after 30 s`() {
        val store = openStore()
        val clock = MutableClock(start)
        val limit = UserRateLimit(1, clock)
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, "session") }, userLimit = limit).install(this) }
            val first = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(202, first.status.value)
            val second = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(429, second.status.value)
            assertEquals("the whole minute is left", "60", second.headers["Retry-After"])
            clock.advance(java.time.Duration.ofSeconds(30))
            val half = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals("30 s later retry after is 30", 429, half.status.value)
            assertEquals("retry after is 30 after 30 s", "30", half.headers["Retry-After"])
            clock.advance(java.time.Duration.ofSeconds(31))
            val reopened = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals("after 60 s a new window opens", 202, reopened.status.value)
        }
        closeAll()
    }

    @Test
    fun `the map stays bounded after the window passes`() {
        val store = openStore()
        val clock = MutableClock(start)
        val limit = UserRateLimit(1, clock)
        var account = 0L
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(account, "session") }
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = tokenCheck, userLimit = limit).install(this) }
            repeat(100) { index ->
                account = index.toLong()
                client.postBearer("/v1/audio/transcriptions", newBody(), "tok-$index")
            }
            assertEquals("100 live windows tracked", 100, limit.size())
            clock.advance(java.time.Duration.ofMinutes(1).plus(java.time.Duration.ofSeconds(1)))
            account = 999L
            val fresh = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-new")
            assertEquals(202, fresh.status.value)
            assertEquals("one live window after a fresh request", 1, limit.size())
        }
        closeAll()
    }

    @Test
    fun `a poll of a job is not limited by the user rate limit`() {
        val store = openStore()
        val limit = UserRateLimit(1, MutableClock(start))
        // Enqueue one job first so there is something to poll.
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, "session") }, userLimit = limit).install(this) }
            val enqueued = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(202, enqueued.status.value)
            // Poll 20 times: the user limit does not guard GET.
            repeat(20) {
                val poll = client.getBearer("/v1/jobs/1", "tok-s")
                assertEquals("poll is not limited by the user rate limit", 200, poll.status.value)
            }
        }
        closeAll()
    }

    @Test
    fun `429 comes before 413 so an oversized upload from a limited account is not read`() {
        val store = openStore()
        var uploadRead = false
        val readMultipart: suspend ApplicationCall.() -> MultiPartData = {
            uploadRead = true
            receiveMultipart()
        }
        val limit = UserRateLimit(1, MutableClock(start))
        testApplication {
            application { TranscriptionApi(store, {}, readMultipart = readMultipart, tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, "session") }, userLimit = limit).install(this) }
            val first = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(202, first.status.value)
            uploadRead = false
            // Oversized body: 25 MiB + 1, from an account already over its cap.
            val oversized = multipartBody(file = ByteArray((25L * 1024 * 1024 + 1).toInt()), model = "m")
            val second = client.postBearer("/v1/audio/transcriptions", oversized, "tok-s")
            assertEquals(429, second.status.value)
            assertTrue("the oversized upload is not read when the account is limited", !uploadRead)
        }
        closeAll()
    }

    @Test
    fun `the refusal body is the fixed text of the limit`() {
        val store = openStore()
        val limit = UserRateLimit(1, MutableClock(start))
        val tokenCheck = FakeTokenCheck { TokenAnswer.Live(7L, "session") }
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = tokenCheck, userLimit = limit).install(this) }
            client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            val refused = client.postBearer("/v1/audio/transcriptions", newBody(), "tok-s")
            assertEquals(429, refused.status.value)
            assertEquals("the body names the limit and nothing else", "{\"error\":\"rate limit exceeded\"}", refused.bodyAsText())
        }
        closeAll()
    }

    @Test
    fun `a not live token is 401 and never counted`() {
        val store = openStore()
        val limit = UserRateLimit(1, MutableClock(start))
        testApplication {
            application { TranscriptionApi(store, {}, tokenCheck = FakeTokenCheck { TokenAnswer.NotLive }, userLimit = limit).install(this) }
            val first = client.postBearer("/v1/audio/transcriptions", newBody(), "dead-token")
            assertEquals(401, first.status.value)
            val second = client.postBearer("/v1/audio/transcriptions", newBody(), "dead-token")
            assertEquals(401, second.status.value)
        }
        closeAll()
    }
}

/** Config parse tests for BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN, mirroring AgentLimitConfigParseTest. */
internal class UserRateLimitConfigParseTest {

    private val baseEnv = mapOf(
        "BREAKER_WHISPER_PORT" to "8080",
        "BREAKER_WHISPER_DB" to "/tmp/test.db",
        "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000",
        "BREAKER_WHISPER_SYNC_API_URL" to "http://sync-api:8081",
        "BREAKER_WHISPER_SYNC_API_SERVICE_KEY" to "svc-key",
    )

    private fun expectFailure(block: () -> Unit): IllegalStateException {
        return try {
            block()
            throw AssertionError("expected IllegalStateException but nothing was thrown")
        } catch (thrown: Throwable) {
            if (thrown is IllegalStateException) thrown
            else throw AssertionError(
                "expected IllegalStateException but got ${thrown::class.java.name}",
                thrown,
            )
        }
    }

    @Test
    fun `rate limit absent means the limit is off`() {
        val config = WhisperConfig.fromEnv(baseEnv)
        assertEquals("no rate limit when the variable is absent", null, config.userRateLimitPerMin)
    }

    @Test
    fun `rate limit one parses`() {
        val env = baseEnv + ("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN" to "1")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("cap 1 parses", 1, config.userRateLimitPerMin)
    }

    @Test
    fun `rate limit ten thousand parses at the upper bound`() {
        val env = baseEnv + ("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN" to "10000")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("cap 10000 parses", 10000, config.userRateLimitPerMin)
    }

    @Test
    fun `rate limit blank refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN" to "   ")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit zero refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN" to "0")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit negative refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN" to "-5")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit not a number refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN" to "abc")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit above ten thousand refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN" to "10001")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN"),
        )
    }
}
