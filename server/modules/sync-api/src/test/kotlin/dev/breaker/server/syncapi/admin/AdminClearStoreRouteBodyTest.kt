package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.ResetRouteTestBase
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class AdminClearStoreRouteBodyTest : ResetRouteTestBase() {

    private val clearPath = "/v1/admin/clear-store"

    private fun postClear(bearer: String?, body: String) = postTo(clearPath, bearer, body)

    private lateinit var adminText: String

    @Before
    fun startTheAdmin() { adminText = startAdmin() }

    @Test
    fun `confirm false gets 400 with an accept control`() {
        register("Alice", 2)
        val refused = postClear(adminText, """{"username":"alice","confirm":false}""")
        assertEquals("confirm false status", HttpStatusCode.BadRequest, refused.status)
        assertEquals(
            "confirm false body",
            """{"error":"bad_request","message":"confirm must be true"}""",
            refused.body,
        )
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("confirm true accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `confirm absent gets 400 with an accept control`() {
        register("Alice", 2)
        val refused = postClear(adminText, """{"username":"alice"}""")
        assertEquals("confirm absent status", HttpStatusCode.BadRequest, refused.status)
        assertEquals(
            "confirm absent body",
            """{"error":"bad_request","message":"confirm must be true"}""",
            refused.body,
        )
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("confirm true accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `confirm not a boolean gets 400`() {
        register("Alice", 2)
        val refused = postClear(adminText, """{"username":"alice","confirm":"yes"}""")
        assertEquals("confirm string status", HttpStatusCode.BadRequest, refused.status)
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `confirm as the string true gets 400`() {
        register("Alice", 2)
        val refused = postClear(adminText, """{"username":"alice","confirm":"true"}""")
        assertEquals(
            "confirm string true status",
            HttpStatusCode.BadRequest,
            refused.status,
        )
        assertEquals(
            "confirm string true body",
            """{"error":"bad_request","message":"The body is not exactly one JSON object with the members username and confirm"}""",
            refused.body,
        )
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `an extra member gets 400`() {
        register("Alice", 2)
        val refused = postClear(adminText, """{"username":"alice","confirm":true,"extra":1}""")
        assertEquals("extra member status", HttpStatusCode.BadRequest, refused.status)
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `username a number gets 400`() {
        register("Alice", 2)
        val refused = postClear(adminText, """{"username":42,"confirm":true}""")
        assertEquals("username number status", HttpStatusCode.BadRequest, refused.status)
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `empty body gets 400`() {
        register("Alice", 2)
        val refused = postClear(adminText, """{}""")
        assertEquals("empty body status", HttpStatusCode.BadRequest, refused.status)
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `not JSON gets 400`() {
        register("Alice", 2)
        val refused = postClear(adminText, """hello""")
        assertEquals("not json status", HttpStatusCode.BadRequest, refused.status)
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `an oversized body gets 413`() {
        register("Alice", 2)
        val padding = " ".repeat(5000)
        val body = """{"username":"alice","confirm":true$padding}"""
        val refused = postClear(adminText, body)
        assertEquals("oversized status", HttpStatusCode.PayloadTooLarge, refused.status)
        assertEquals(
            "oversized body",
            """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}""",
            refused.body,
        )
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `a malformed body gets the shape answer with an accept control`() {
        register("Alice", 2)
        val shape = "The body is not exactly one JSON object with the members username and confirm"
        val bodies = listOf(
            """{"username":"alice","username":"alice","confirm":true}""",
            """{"username":"alice","confirm":true,"confirm":true}""",
            """{"username":"alice","confirm":true,"extra":"x"}""",
            """{"username":true}""",
            """{"username":"alice","confirm":null}""",
            """{"username":"alice","confirm":true,}""",
            """{,"username":"alice","confirm":true}""",
            """{"username":"alice" "confirm":true}""",
            """{username:"alice","confirm":true}""",
            """{"username":nullx,"confirm":true}""",
            """{"username":"alice","confirm":truex}""",
            """[]""",
        )
        for (body in bodies) {
            val refused = postClear(adminText, body)
            assertEquals("shape status for $body", HttpStatusCode.BadRequest, refused.status)
            assertEquals(
                "shape body for $body",
                """{"error":"bad_request","message":"$shape"}""",
                refused.body,
            )
        }
        val accepted = postClear(adminText, """{"username":"alice","confirm":true}""")
        assertEquals("accept control status", HttpStatusCode.OK, accepted.status)
    }

    @Test
    fun `a name in another letter case finds the account`() {
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val cleared = postClear(adminText, """{"username":"ALICE","confirm":true}""")
        assertEquals("upper case name status", HttpStatusCode.OK, cleared.status)
        assertEquals("upper case name count", """{"cleared":1}""", cleared.body)
        // Accept control: a name that is not an account is still a 404.
        val unknown = postClear(adminText, """{"username":"ALICEX","confirm":true}""")
        assertEquals("unknown name status", HttpStatusCode.NotFound, unknown.status)
    }
}
