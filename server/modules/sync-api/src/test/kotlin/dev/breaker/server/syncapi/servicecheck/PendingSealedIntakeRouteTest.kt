package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.db.MAX_SEALED_BYTES
import dev.breaker.server.syncapi.db.PENDING_PER_ACCOUNT_MAX
import dev.breaker.server.syncapi.db.PendingInsertResult
import dev.breaker.server.syncapi.db.PendingSealedRow
import dev.breaker.server.syncapi.db.PendingSealedStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SYNC_BODY_CAP_BYTES
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * POST /internal/v1/pending-sealed: every status with its whole body, the two 413 paths (the body cap by declared
 * length and by the bytes read, and MAX_SEALED_BYTES on the decoded copy), the boundary of the per-account cap,
 * the key check before anything is read, a failing store, and a cancelled one. Rows are read back with list.
 */
internal class PendingSealedIntakeRouteTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private lateinit var temp: TempDatabase
    private lateinit var store: PendingSealedStore
    private var alice = 0L
    private var bob = 0L
    private val oversize = ArrayList<Pair<Long, Int>>()
    private val tooLargeBody = """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}"""
    private val badRequestBody = """{"error":"bad_request"}"""
    private val fixedNowMs = 1_700_000_000_123L
    private val fixedNowText = "2023-11-14T22:13:20.123Z"

    @Before
    fun setup() {
        temp = openTemp()
        store = PendingSealedStore(temp.db)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun uuid(number: Int): String = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')

    private fun sealedOf(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { (it % 251).toByte() })

    private fun bodyOf(account: Long, job: String, sealed: String): String =
        """{"account_id":$account,"job_id":"$job","sealed_b64":"$sealed"}"""

    private fun good(account: Long, number: Int, sealed: String = sealedOf(60)): String =
        bodyOf(account, uuid(number), sealed)

    private fun rows(account: Long): List<PendingSealedRow> = runBlocking { store.list(account) }

    private fun seed(account: Long, count: Int) {
        for (number in 1..count) {
            val result = runBlocking { store.insert(account, uuid(number), "box$number", "2026-11-13T00:00:00.000Z") }
            assertEquals("sync-api: seed row $number", PendingInsertResult.Stored, result)
        }
    }

    private fun mount(
        insert: suspend (Long, String, String, String) -> PendingInsertResult,
    ): Application.() -> Unit = {
        routing {
            pendingSealedIntakeRoute(
                "test-key",
                insert,
                onOversize = { account, size -> oversize.add(account to size) },
                nowMs = { fixedNowMs },
            )
        }
    }

    private fun send(
        text: String,
        key: String? = "test-key",
        insert: suspend (Long, String, String, String) -> PendingInsertResult = store::insert,
    ): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application(mount(insert))
                val response = client.post("/internal/v1/pending-sealed") {
                    if (key != null) header("X-Service-Key", key)
                    setBody(text)
                    contentType(ContentType.Application.Json)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    // A body sent without a declared length, the way SyncRouteTestSupport sends its chunked case.
    private fun postChunked(bytes: ByteArray): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application(mount(store::insert))
                val response = client.post("/internal/v1/pending-sealed") {
                    header("X-Service-Key", "test-key")
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                    })
                    contentType(ContentType.Application.Json)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    private fun padTo(body: String, size: Long): String {
        require(body.length <= size) { "body too long: ${body.length} > $size" }
        return body + " ".repeat((size - body.length).toInt())
    }

    // --- the answers ---------------------------------------------------------------------------

    @Test
    fun `a good copy is stored and answered 201 with stored true`() {
        val sealed = sealedOf(60)
        val reply = send(good(alice, 1, sealed))
        assertEquals("sync-api: status", HttpStatusCode.Created, reply.status)
        assertEquals("sync-api: body", """{"stored":true}""", reply.body)
        assertEquals("sync-api: the row", listOf(PendingSealedRow(uuid(1), sealed, fixedNowText)), rows(alice))
    }

    @Test
    fun `the same job id again is 200 with stored false and the first row stays`() {
        val first = sealedOf(60)
        send(good(alice, 1, first))
        val reply = send(good(alice, 1, sealedOf(70)))
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", """{"stored":false}""", reply.body)
        assertEquals("sync-api: the first row's bytes", listOf(PendingSealedRow(uuid(1), first, fixedNowText)), rows(alice))
    }

    @Test
    fun `a job id held by another account is 200 with stored false and nothing says whose`() {
        send(good(alice, 1))
        val reply = send(good(bob, 1))
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", """{"stored":false}""", reply.body)
        assertEquals("sync-api: bob holds nothing", emptyList<PendingSealedRow>(), rows(bob))
    }

    @Test
    fun `an account that does not exist is 404 and nothing is stored`() {
        val reply = send(good(alice + bob + 100L, 1))
        assertEquals("sync-api: status", HttpStatusCode.NotFound, reply.status)
        assertEquals("sync-api: body", """{"error":"no_such_account"}""", reply.body)
        assertEquals("sync-api: alice holds nothing", emptyList<PendingSealedRow>(), rows(alice))
    }

    // --- the cap of rows -----------------------------------------------------------------------

    @Test
    fun `the 100th copy is stored and the 101st is 409 pending full`() {
        seed(alice, PENDING_PER_ACCOUNT_MAX - 1)
        val hundredth = send(good(alice, 100))
        assertEquals("sync-api: the 100th status", HttpStatusCode.Created, hundredth.status)
        assertEquals("sync-api: the 100th body", """{"stored":true}""", hundredth.body)
        assertEquals("sync-api: rows after 100", 100, rows(alice).size)
        val over = send(good(alice, 101))
        assertEquals("sync-api: the 101st status", HttpStatusCode.Conflict, over.status)
        assertEquals("sync-api: the 101st body", """{"error":"pending_full"}""", over.body)
        assertEquals("sync-api: rows after the 101st", 100, rows(alice).size)
        assertTrue("sync-api: the 101st id is not a row", rows(alice).none { it.id == uuid(101) })
    }

    @Test
    fun `a duplicate job id at 100 rows is still 200 with stored false`() {
        seed(alice, PENDING_PER_ACCOUNT_MAX)
        val reply = send(good(alice, 7))
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", """{"stored":false}""", reply.body)
        assertEquals("sync-api: rows", 100, rows(alice).size)
        assertEquals("sync-api: the row text is the seeded one", "box7", rows(alice).first { it.id == uuid(7) }.sealedBox)
    }

    // --- the 413 paths -------------------------------------------------------------------------

    @Test
    fun `a body one byte over the cap by declared length is 413 and nothing is stored`() {
        val over = padTo(good(alice, 1), SYNC_BODY_CAP_BYTES + 1)
        val reply = send(over)
        assertEquals("sync-api: status", HttpStatusCode.PayloadTooLarge, reply.status)
        assertEquals("sync-api: body", tooLargeBody, reply.body)
        assertEquals("sync-api: no row", emptyList<PendingSealedRow>(), rows(alice))
        assertEquals("sync-api: a body cap refusal is not a size log", emptyList<Pair<Long, Int>>(), oversize)
    }

    @Test
    fun `a body of exactly the cap is accepted`() {
        val reply = send(padTo(good(alice, 1), SYNC_BODY_CAP_BYTES))
        assertEquals("sync-api: status", HttpStatusCode.Created, reply.status)
        assertEquals("sync-api: body", """{"stored":true}""", reply.body)
    }

    @Test
    fun `a chunked body one byte over the cap is 413 and one at the cap is accepted`() {
        val over = padTo(good(alice, 1), SYNC_BODY_CAP_BYTES + 1).toByteArray()
        val refused = postChunked(over)
        assertEquals("sync-api: over status", HttpStatusCode.PayloadTooLarge, refused.status)
        assertEquals("sync-api: over body", tooLargeBody, refused.body)
        assertEquals("sync-api: no row after the refusal", emptyList<PendingSealedRow>(), rows(alice))
        val exact = postChunked(padTo(good(alice, 2), SYNC_BODY_CAP_BYTES).toByteArray())
        assertEquals("sync-api: exact status", HttpStatusCode.Created, exact.status)
        assertEquals("sync-api: exact body", """{"stored":true}""", exact.body)
    }

    @Test
    fun `a sealed copy over MAX_SEALED_BYTES is 413 and nothing is stored but the size is logged`() {
        val over = good(alice, 1, sealedOf(MAX_SEALED_BYTES + 1))
        assertTrue("sync-api: control, the body is under the body cap so only the sealed limit can refuse", over.length < SYNC_BODY_CAP_BYTES)
        val reply = send(over)
        assertEquals("sync-api: status", HttpStatusCode.PayloadTooLarge, reply.status)
        assertEquals("sync-api: body", tooLargeBody, reply.body)
        assertEquals("sync-api: no row", emptyList<PendingSealedRow>(), rows(alice))
        assertEquals("sync-api: one log call with the account id and the size", listOf(alice to MAX_SEALED_BYTES + 1), oversize)
    }

    @Test
    fun `a sealed copy of exactly MAX_SEALED_BYTES is stored`() {
        val sealed = sealedOf(MAX_SEALED_BYTES)
        val reply = send(good(alice, 1, sealed))
        assertEquals("sync-api: status", HttpStatusCode.Created, reply.status)
        assertEquals("sync-api: body", """{"stored":true}""", reply.body)
        assertEquals("sync-api: the row text", sealed, rows(alice).single().sealedBox)
        assertEquals("sync-api: no size log", emptyList<Pair<Long, Int>>(), oversize)
    }

    // --- bad bodies ----------------------------------------------------------------------------

    @Test
    fun `every bad body is 400 with the fixed body and stores nothing`() {
        val canonical49 = sealedOf(49)
        assertTrue("sync-api: control, the 49 byte text ends in MA==", canonical49.endsWith("MA=="))
        val trailingBits = canonical49.dropLast(3) + "B=="
        val noPadding = sealedOf(50).trimEnd('=')
        val cases = listOf(
            "not json" to "nope",
            "empty" to "",
            "an array" to "[]",
            "no account id" to """{"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}"}""",
            "no job id" to """{"account_id":$alice,"sealed_b64":"${sealedOf(60)}"}""",
            "no sealed" to """{"account_id":$alice,"job_id":"${uuid(1)}"}""",
            "a fourth member" to """{"account_id":$alice,"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}","x":1}""",
            "account id as text" to """{"account_id":"$alice","job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}"}""",
            "account id zero" to """{"account_id":0,"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}"}""",
            "account id negative" to """{"account_id":-$alice,"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}"}""",
            "account id fraction" to """{"account_id":$alice.5,"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}"}""",
            "account id exponent" to """{"account_id":${alice}e0,"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}"}""",
            "account id leading zero" to """{"account_id":0$alice,"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}"}""",
            "job id upper case" to bodyOf(alice, "AAAAAAAA-0000-0000-0000-000000000001", sealedOf(60)),
            "job id too short" to bodyOf(alice, "0000", sealedOf(60)),
            "job id a number" to """{"account_id":$alice,"job_id":1,"sealed_b64":"${sealedOf(60)}"}""",
            "sealed a number" to """{"account_id":$alice,"job_id":"${uuid(1)}","sealed_b64":1}""",
            "sealed not base64" to bodyOf(alice, uuid(1), "!!!!"),
            "sealed with a newline" to """{"account_id":$alice,"job_id":"${uuid(1)}","sealed_b64":"${sealedOf(60)}\n"}""",
            "sealed without padding" to bodyOf(alice, uuid(1), noPadding),
            "sealed with trailing bits set" to bodyOf(alice, uuid(1), trailingBits),
            "sealed empty" to bodyOf(alice, uuid(1), ""),
            "sealed of 48 bytes" to bodyOf(alice, uuid(1), sealedOf(48)),
        )
        for ((label, text) in cases) {
            val reply = send(text)
            assertEquals("sync-api: $label status", HttpStatusCode.BadRequest, reply.status)
            assertEquals("sync-api: $label body", badRequestBody, reply.body)
        }
        assertEquals("sync-api: no row after all of them", emptyList<PendingSealedRow>(), rows(alice))
        val control = send(bodyOf(alice, uuid(1), canonical49))
        assertEquals("sync-api: control, a copy of 49 bytes is stored", HttpStatusCode.Created, control.status)
    }

    // --- the key, a failing store, a cancelled one ---------------------------------------------

    @Test
    fun `a wrong or missing key is 403 and the insert is never called`() {
        var calls = 0
        val counting: suspend (Long, String, String, String) -> PendingInsertResult = { account, id, box, at ->
            calls++
            store.insert(account, id, box, at)
        }
        for (key in listOf("wrong-key", null, "")) {
            val reply = send(good(alice, 1), key = key, insert = counting)
            assertEquals("sync-api: status for key $key", HttpStatusCode.Forbidden, reply.status)
            assertEquals("sync-api: body for key $key", """{"error":"bad_service_key"}""", reply.body)
        }
        val tooBig = send(padTo(good(alice, 1), SYNC_BODY_CAP_BYTES + 1), key = "wrong-key", insert = counting)
        assertEquals("sync-api: a wrong key on a huge body is 403, not 413", HttpStatusCode.Forbidden, tooBig.status)
        assertEquals("sync-api: insert calls", 0, calls)
        assertEquals("sync-api: no row", emptyList<PendingSealedRow>(), rows(alice))
        val control = send(good(alice, 1), insert = counting)
        assertEquals("sync-api: control, the right key stores", HttpStatusCode.Created, control.status)
        assertEquals("sync-api: control insert calls", 1, calls)
    }

    @Test
    fun `a failing store is the fixed 503 with no exception text`() {
        temp.db.close()
        val reply = send(good(alice, 1))
        assertEquals("sync-api: status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: body", """{"error":"unavailable"}""", reply.body)
    }

    @Test
    fun `a cancelled insert is passed on and not answered`() {
        var sawCancellation = false
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application {
                    routing {
                        post("/t") {
                            try {
                                servePendingSealedIntake(
                                    call,
                                    ServiceKey("test-key"),
                                    { _, _, _, _ -> throw CancellationException("stop") },
                                    { _, _ -> },
                                    { fixedNowMs },
                                )
                            } catch (_: CancellationException) {
                                sawCancellation = true
                                call.respondText("cancelled", status = HttpStatusCode.OK)
                            }
                        }
                    }
                }
                val response = client.post("/t") {
                    header("X-Service-Key", "test-key")
                    setBody(good(alice, 1))
                    contentType(ContentType.Application.Json)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        assertTrue("sync-api: the cancellation must reach the caller", sawCancellation)
        assertEquals("sync-api: status", HttpStatusCode.OK, checkNotNull(reply).status)
        assertEquals("sync-api: body", "cancelled", checkNotNull(reply).body)
    }

    @Test
    fun `a plain failure is not taken for a cancellation`() {
        val reply = send(good(alice, 1), insert = { _, _, _, _ -> throw IllegalStateException("closed") })
        assertEquals("sync-api: status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: body", """{"error":"unavailable"}""", reply.body)
        assertFalse("sync-api: no exception text", reply.body.contains("closed"))
    }
}
