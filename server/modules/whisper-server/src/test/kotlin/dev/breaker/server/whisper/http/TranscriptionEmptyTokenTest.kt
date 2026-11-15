package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Authorization header that names the Bearer scheme but carries no token is refused with the
 * same plain 401 as a missing header, a foreign scheme or a not-live token, and the token check
 * is never asked. The 503 answer belongs to a check that could not be made; here there was
 * nothing to check, so a caller must not be able to tell an empty token from a bad one.
 */
internal class TranscriptionEmptyTokenTest {

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

    private val emptyTokenHeaders = listOf("Bearer ", "Bearer    ", "BEARER  ")

    @Test
    fun `enqueue with the Bearer scheme and no token is refused with 401 and asks nobody`() {
        for (value in emptyTokenHeaders) {
            val store = openStore()
            val check = FakeTokenCheck { TokenAnswer.Live(1L) }
            withTranscriptionApi(store, tokenCheck = check) { client ->
                val response = client.post("/v1/audio/transcriptions") {
                    header("Authorization", value)
                    setBody(multipartBody(file = JobFixtures.audio(1), model = "m"))
                }
                assertEquals(401, response.status.value)
                assertEquals("body for [$value]", """{"error":"unauthorized"}""", response.bodyAsText())
                assertTrue("the token check is not asked for [$value]", check.ops.isEmpty())
                assertTrue("nothing is enqueued for [$value]", store.get(1) == null)
            }
        }
    }

    @Test
    fun `poll with the Bearer scheme and no token is refused with 401 and asks nobody`() {
        for (value in emptyTokenHeaders) {
            val store = openStore()
            val check = FakeTokenCheck { TokenAnswer.Live(1L) }
            withTranscriptionApi(store, tokenCheck = check) { client ->
                val response = client.get("/v1/jobs/1") {
                    header("Authorization", value)
                }
                assertEquals(401, response.status.value)
                assertEquals("body for [$value]", """{"error":"unauthorized"}""", response.bodyAsText())
                assertTrue("the token check is not asked for [$value]", check.ops.isEmpty())
            }
        }
    }
}
