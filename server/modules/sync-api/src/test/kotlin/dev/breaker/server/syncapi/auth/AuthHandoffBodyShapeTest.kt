package dev.breaker.server.syncapi.auth

import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bodies that are not one well-formed JSON object of string members: every one is a 400 on both routes.
 * The first group checks that no shape reaches a 500; the exchange group has valid members, so only the
 * punctuation rules (commas, a missing value) can make it a 400.
 */
internal class AuthHandoffBodyShapeTest : HandoffRouteTestBase() {

    private val anyBodies: List<String> = listOf(
        "",
        "{}",
        "[]",
        "{\"a\":\"x\",}",
        "{,\"a\":\"x\"}",
        "{\"a\":\"x\" \"b\":\"y\"}",
        "{\"a\":}",
        "{\"a\":\"",
        "{\"a\":\"\\u12\"}",
        "{\"a\":\"\\u+041\"}",
        "{\"a\":\"\\uZZZZ\"}",
        "{\"a\":\"x\",\"a\":\"y\"}",
        "{\"a\":1}",
        " ".repeat(4096),
    )

    private val unusedCode: String = "C".repeat(43)

    private val exchangeBodies: List<String> = listOf(
        "{\"code\":\"$unusedCode\" \"secret\":\"$secret\"}",
        "{,\"code\":\"$unusedCode\",\"secret\":\"$secret\"}",
        "{\"code\":\"$unusedCode\",\"secret\":\"$secret\",}",
        "{\"code\":\"$unusedCode\",\"secret\":}",
        "{\"code\":\"$unusedCode\",\"secret\":\"$secret\",\"device_name\":}",
    )

    @Test fun `start answers 400 for every malformed body`() {
        val token = sessionToken(register("alice", 1))
        for (body in anyBodies) {
            val reply = postTo("/v1/auth/handoff/start", token, body)
            assertEquals("status for start body [$body]", HttpStatusCode.BadRequest, reply.status)
            assertEquals("answer for start body [$body]", badRequestBody, reply.body)
        }
    }

    @Test fun `exchange answers 400 for every malformed body`() {
        for (body in anyBodies + exchangeBodies) {
            val reply = exch(body)
            assertEquals("status for exchange body [$body]", HttpStatusCode.BadRequest, reply.status)
            assertEquals("answer for exchange body [$body]", badRequestBody, reply.body)
        }
    }
}
