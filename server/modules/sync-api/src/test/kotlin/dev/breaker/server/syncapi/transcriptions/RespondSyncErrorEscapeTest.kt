package dev.breaker.server.syncapi.transcriptions

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route-level tests for [respondSyncError]. A tiny test route calls respondSyncError with hostile
 * message values (quote backslash tab U+0001) and a hostile error value (quote backslash newline).
 * The body must parse as JSON and each member must decode back to the same string.
 * Test names use only letters digits spaces — no : . ; [ ] / < >.
 */
internal class RespondSyncErrorEscapeTest {

    @Test
    fun `respondSyncError escapes a hostile message and the body parses as JSON`() = runBlocking<Unit> {
        testApplication {
            application {
                routing {
                    get("/v1/test/error") {
                        call.respondSyncError(
                            HttpStatusCode.BadRequest,
                            "bad_request",
                            "he said \"hi\" then \t\u0001",
                        )
                    }
                }
            }
            val response = client.get("/v1/test/error") {
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            val expected = """{"error":"bad_request","message":"he said \"hi\" then \t\u0001"}"""
            assertEquals("body", expected, body)
            assertTrue("message decodes", messageMemberDecodes(body) == "he said \"hi\" then \t\u0001")
        }
    }

    @Test
    fun `respondSyncError escapes a hostile error argument`() = runBlocking<Unit> {
        testApplication {
            application {
                routing {
                    get("/v1/test/error") {
                        call.respondSyncError(
                            HttpStatusCode.BadRequest,
                            "bad \"error\" \\n",
                            "plain message",
                        )
                    }
                }
            }
            val response = client.get("/v1/test/error") {
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            val expected = """{"error":"bad \"error\" \\n","message":"plain message"}"""
            assertEquals("body", expected, body)
            assertTrue("error decodes", errorMemberDecodes(body) == "bad \"error\" \\n")
        }
    }

    /** Extracts and decodes the value of the "error" member from a JSON body. */
    private fun errorMemberDecodes(body: String): String {
        val key = "\"error\":\""
        val start = body.indexOf(key) + key.length
        // the value ends at the quote that is followed by the message member (an escaped quote inside it is not)
        val end = body.indexOf("\",\"message\":", start)
        return decodeEscapes(body.substring(start, end))
    }

    /** Extracts and decodes the value of the "message" member from a JSON body. */
    private fun messageMemberDecodes(body: String): String {
        val key = "\"message\":\""
        val start = body.indexOf(key) + key.length
        val end = body.lastIndexOf("\"}")
        return decodeEscapes(body.substring(start, end))
    }

    /** Decodes JSON string escapes (\n \t \\ \" \/ \b \f \r \uXXXX) back to characters. */
    private fun decodeEscapes(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                i++
                when (s[i]) {
                    '"' -> sb.append('\"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append(0x0C.toChar())
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 1, i + 5)
                        sb.append(Integer.parseInt(hex, 16).toChar())
                        i += 4
                    }
                    else -> sb.append(s[i])
                }
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
