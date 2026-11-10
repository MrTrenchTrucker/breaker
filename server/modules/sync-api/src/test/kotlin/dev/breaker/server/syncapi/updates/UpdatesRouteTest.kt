package dev.breaker.server.syncapi.updates

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

// GET /v1/updates/latest.json: public, read from a manifest file. No path or no file is 404, a
// file that is there but unusable is 503, a valid file is 200 with a body rebuilt from its six fields.
internal class UpdatesRouteTest {

    private class Reply(val status: HttpStatusCode, val body: String, val type: ContentType?)

    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore
    private lateinit var handoffStore: HandoffStore
    private lateinit var transcriptionStore: TranscriptionStore
    private lateinit var folder: Path

    @Before
    fun setUp() {
        val temp = TestDatabases.openTemp()
        accountStore = AccountFixtures.newStore(temp)
        tokenStore = TokenFixtures.tokenStore(temp, Clock.systemUTC())
        handoffStore = HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock)
        transcriptionStore = SyncRouteTestSupport.newStore(temp)
        folder = Files.createTempDirectory("updates-route-test")
    }

    @After
    fun removeFolder() {
        folder.toFile().deleteRecursively()
    }

    private val notFoundBody = """{"error":"not_found","message":"No release yet"}"""
    private val unavailableBody = """{"error":"unavailable","message":"The service is unavailable, try again later"}"""

    private fun manifestJson(
        version: String = "\"0.0.0.1\"",
        versionCode: String = "2",
        notes: String = "\"Latest release\"",
    ): String =
        "{\"version\":$version,\"version_code\":$versionCode,\"apk_url\":\"https://example.test/app.apk\"," +
            "\"sha256\":\"aaaa\",\"signature\":\"bbbb\",\"notes\":$notes}"

    private fun servedBody(versionCode: String = "2", notes: String = "Latest release"): String =
        "{\"version\":\"0.0.0.1\",\"version_code\":$versionCode,\"apk_url\":\"https://example.test/app.apk\"," +
            "\"sha256\":\"aaaa\",\"signature\":\"bbbb\",\"notes\":\"$notes\"}"

    private fun fileWith(text: String): String = fileWithBytes(text.toByteArray(Charsets.UTF_8))

    private fun fileWithBytes(bytes: ByteArray): String {
        val file = Files.createTempFile(folder, "manifest", ".json")
        Files.write(file, bytes)
        return file.toString()
    }

    private fun fetch(manifestFile: String, path: String = "/v1/updates/latest.json", bearer: Boolean = false): Reply =
        runBlocking {
            var reply: Reply? = null
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore, manifestFile) }
                val response = client.get(path) {
                    if (bearer) header("Authorization", "Bearer " + "x".repeat(43))
                }
                reply = Reply(response.status, response.bodyAsText(), response.contentType()?.withoutParameters())
            }
            reply!!
        }

    private fun assertUnavailable(reply: Reply) {
        assertEquals("status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("body", unavailableBody, reply.body)
        assertEquals("content type", ContentType.Application.Json, reply.type)
    }

    @Test
    fun `no manifest path answers 404 with the fixed body`() {
        val reply = fetch("")
        assertEquals("status", HttpStatusCode.NotFound, reply.status)
        assertEquals("body", notFoundBody, reply.body)
        assertEquals("content type", ContentType.Application.Json, reply.type)
    }

    @Test
    fun `a manifest path with no file answers 404 with the fixed body`() {
        val reply = fetch(folder.resolve("none.json").toString())
        assertEquals("status", HttpStatusCode.NotFound, reply.status)
        assertEquals("body", notFoundBody, reply.body)
    }

    @Test
    fun `a manifest path in a folder that is missing answers 404`() {
        val reply = fetch(folder.resolve("none").resolve("release.json").toString())
        assertEquals("status", HttpStatusCode.NotFound, reply.status)
        assertEquals("body", notFoundBody, reply.body)
    }

    @Test
    fun `a manifest path that is a folder answers 503`() {
        assertUnavailable(fetch(folder.toString()))
    }

    @Test
    fun `an empty manifest file answers 503`() {
        assertUnavailable(fetch(fileWith("")))
    }

    @Test
    fun `a manifest file that is not valid UTF-8 answers 503`() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        assertUnavailable(fetch(fileWithBytes(bytes)))
    }

    @Test
    fun `a manifest file that is not JSON answers 503 and nothing of its text is sent`() {
        val reply = fetch(fileWith("LEAK-MARKER this is not JSON"))
        assertUnavailable(reply)
        assertFalse("the file text must not reach the body", reply.body.contains("LEAK-MARKER"))
    }

    @Test
    fun `a valid manifest file of exactly the size cap is served`() {
        val shell = manifestJson(notes = "\"\"")
        val padding = "x".repeat(MANIFEST_CAP - shell.length)
        val text = manifestJson(notes = "\"$padding\"")
        assertEquals("fixture size", MANIFEST_CAP, text.length)
        val reply = fetch(fileWith(text))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", servedBody(notes = padding), reply.body)
    }

    @Test
    fun `a valid manifest file one byte over the size cap answers 503`() {
        val shell = manifestJson(notes = "\"\"")
        val padding = "x".repeat(MANIFEST_CAP + 1 - shell.length)
        val text = manifestJson(notes = "\"$padding\"")
        assertEquals("fixture size", MANIFEST_CAP + 1, text.length)
        assertUnavailable(fetch(fileWith(text)))
    }

    @Test
    fun `a manifest padded with white space one byte over the size cap answers 503`() {
        // Valid JSON plus trailing white space: a reader that cut the file at the cap would still parse it.
        val shell = manifestJson()
        val text = shell + " ".repeat(MANIFEST_CAP + 1 - shell.length)
        assertEquals("fixture size", MANIFEST_CAP + 1, text.length)
        assertUnavailable(fetch(fileWith(text)))
    }

    @Test
    fun `a bad UTF-8 byte pair inside a string value answers 503`() {
        // C3 28 sits inside notes of an otherwise valid manifest: a decoder that replaced bad bytes would serve 200.
        val text = manifestJson(notes = "\"a#b\"")
        val cut = text.indexOf('#')
        val bytes = text.substring(0, cut).toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xC3.toByte(), 0x28) +
            text.substring(cut + 1).toByteArray(Charsets.UTF_8)
        assertUnavailable(fetch(fileWithBytes(bytes)))
    }

    @Test
    fun `a quote a backslash and a line break in notes are escaped in the body`() {
        val escaped = "say \\\"hi\\\" a\\\\b\\nc"
        val reply = fetch(fileWith(manifestJson(notes = "\"" + escaped + "\"")))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", servedBody(notes = escaped), reply.body)
    }

    @Test
    fun `a valid manifest answers 200 with its six fields in order`() {
        val reply = fetch(fileWith(manifestJson()))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", servedBody(), reply.body)
        assertEquals("content type", ContentType.Application.Json, reply.type)
    }

    @Test
    fun `extra members white space and key order in the file are not echoed`() {
        val text = "{ \"extra\" : \"left out\" ,\n \"notes\" : \"Latest release\" , \"signature\":\"bbbb\", " +
            "\"sha256\":\"aaaa\", \"apk_url\":\"https://example.test/app.apk\", \"version_code\": 2, " +
            "\"version\":\"0.0.0.1\" }\n"
        val reply = fetch(fileWith(text))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", servedBody(), reply.body)
    }

    @Test
    fun `a manifest with empty notes is served`() {
        val reply = fetch(fileWith(manifestJson(notes = "\"\"")))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", servedBody(notes = ""), reply.body)
    }

    @Test
    fun `a quote in a field is escaped in the body`() {
        val reply = fetch(fileWith(manifestJson(version = "\"1\\\"2\"")))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", servedBody().replace("\"0.0.0.1\"", "\"1\\\"2\""), reply.body)
    }

    @Test
    fun `a bearer header changes nothing because the route is public`() {
        val path = fileWith(manifestJson())
        val without = fetch(path)
        val authed = fetch(path, bearer = true)
        assertEquals("status without", HttpStatusCode.OK, without.status)
        assertEquals("status with", HttpStatusCode.OK, authed.status)
        assertEquals("same body", without.body, authed.body)
    }

    @Test
    fun `health still answers 200 when the manifest file is missing`() {
        val reply = fetch(folder.resolve("none.json").toString(), path = "/health")
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", """{"status":"ok"}""", reply.body)
    }

    @Test
    fun `a blank version answers 503`() {
        assertUnavailable(fetch(fileWith(manifestJson(version = "\"  \""))))
    }

    @Test
    fun `a version that is not a string answers 503`() {
        assertUnavailable(fetch(fileWith(manifestJson(version = "5"))))
    }

    @Test
    fun `a version code of zero answers 503`() {
        assertUnavailable(fetch(fileWith(manifestJson(versionCode = "0"))))
    }

    @Test
    fun `a version code written as a string answers 503`() {
        assertUnavailable(fetch(fileWith(manifestJson(versionCode = "\"2\""))))
    }

    @Test
    fun `a version code with a leading zero or a fraction or a sign answers 503`() {
        for (code in listOf("02", "2.0", "-2", "2e1")) {
            assertUnavailable(fetch(fileWith(manifestJson(versionCode = code))))
        }
    }

    @Test
    fun `a version code of nineteen digits answers 503`() {
        assertUnavailable(fetch(fileWith(manifestJson(versionCode = "1".repeat(19)))))
    }

    @Test
    fun `a manifest without notes answers 503`() {
        val text = "{\"version\":\"0.0.0.1\",\"version_code\":2,\"apk_url\":\"https://example.test/app.apk\"," +
            "\"sha256\":\"aaaa\",\"signature\":\"bbbb\"}"
        assertUnavailable(fetch(fileWith(text)))
    }

    @Test
    fun `notes that are not a string answer 503`() {
        assertUnavailable(fetch(fileWith(manifestJson(notes = "7"))))
    }
}
