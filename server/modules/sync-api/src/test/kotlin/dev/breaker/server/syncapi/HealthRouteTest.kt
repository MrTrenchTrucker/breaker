package dev.breaker.server.syncapi

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRouteTest {

    @Test
    fun `GET health answers 200`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule() }
            val response = client.get("/health")
            assertEquals("GET /health status", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `GET health body is exactly the status ok object`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule() }
            val response = client.get("/health")
            assertEquals("GET /health body", """{"status":"ok"}""", response.bodyAsText())
        }
    }

    @Test
    fun `GET health is served as json`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule() }
            val response = client.get("/health")
            assertEquals(
                "GET /health content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    @Test
    fun `GET health works without any Authorization header`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule() }
            // No header is added to this client call: liveness must not depend on a login.
            val response = client.get("/health")
            assertEquals("unauthenticated GET /health status", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `POST to health is not a success and is answered 404 or 405`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule() }
            val response = client.post("/health")
            val code = response.status.value
            assertFalse("POST /health must not be a 2xx but was $code", code in 200..299)
            assertTrue("POST /health must be 404 or 405 but was $code", code == 404 || code == 405)
        }
    }

    @Test
    fun `an unknown path is answered 404`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule() }
            val response = client.get("/nope")
            assertEquals("GET /nope status", HttpStatusCode.NotFound, response.status)
        }
    }
}
