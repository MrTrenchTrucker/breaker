package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.sync.PermanentRefusalException
import dev.breaker.dictation.sync.UploadOutcome
import dev.breaker.shared.api.SyncPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A 429 from the sync push carries its Retry-After seconds on the refusal; no other status does. */
class SyncHttpUploaderRetryAfterTest {

    private val limits = CallLimits(10_000, 30_000, 1_048_576L)

    private fun session(): AddressedSession = AddressedSession(
        "https://server.test",
        AuthSession("u1", "user", "token-a", UserRole.USER, setOf(TokenScope.TRANSCRIBE)),
    )

    private fun push(): SyncPush =
        SyncPush("0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f", 0, "eyJ2IjoxfQ==", null)

    /** A fake transport that answers every request with one canned reply. No network. */
    private class FakeTransport : HttpTransport {
        var reply: BytesReply = BytesReply(204, ByteArray(0))

        override fun send(request: CallRequest): BytesReply = reply

        override fun open(request: CallRequest): dev.breaker.dictation.http.StreamReply =
            throw UnsupportedOperationException("open is not used by the uploader")
    }

    private fun uploader(transport: FakeTransport): SyncHttpUploader =
        SyncHttpUploader(transport, limits) { session() }

    @Test
    fun `429 throws PermanentRefusalException carrying retryAfterSeconds`() {
        val transport = FakeTransport().apply {
            reply = BytesReply(
                429,
                ByteArray(0),
                headers = listOf("Retry-After" to "45"),
            )
        }
        val up = uploader(transport)
        val thrown = try {
            up.upload(push())
            null
        } catch (e: PermanentRefusalException) { e }
        val refusal = thrown ?: throw AssertionError("a 429 was not a PermanentRefusalException")
        assertEquals("status is 429", 429, refusal.status)
        assertEquals("retryAfterSeconds is 45", 45, refusal.retryAfterSeconds)
    }

    @Test
    fun `429 with bad Retry-After header carries null retryAfterSeconds`() {
        val transport = FakeTransport().apply {
            reply = BytesReply(
                429,
                ByteArray(0),
                headers = listOf("Retry-After" to "not-a-number"),
            )
        }
        val up = uploader(transport)
        val thrown = try {
            up.upload(push())
            null
        } catch (e: PermanentRefusalException) { e }
        val refusal = thrown ?: throw AssertionError("a 429 was not a PermanentRefusalException")
        assertEquals("status is 429", 429, refusal.status)
        assertNull("bad retryAfter carries null", refusal.retryAfterSeconds)
    }

    @Test
    fun `429 with out-of-bounds Retry-After carries null retryAfterSeconds`() {
        val transport = FakeTransport().apply {
            reply = BytesReply(
                429,
                ByteArray(0),
                headers = listOf("Retry-After" to "99999"),
            )
        }
        val up = uploader(transport)
        val thrown = try {
            up.upload(push())
            null
        } catch (e: PermanentRefusalException) { e }
        val refusal = thrown ?: throw AssertionError("a 429 was not a PermanentRefusalException")
        assertEquals("status is 429", 429, refusal.status)
        assertNull("out-of-bounds retryAfter carries null", refusal.retryAfterSeconds)
    }

    private fun refusalOf(answer: BytesReply): PermanentRefusalException {
        val transport = FakeTransport().apply { reply = answer }
        try {
            uploader(transport).upload(push())
        } catch (e: PermanentRefusalException) {
            return e
        }
        throw AssertionError("the reply was not refused")
    }

    @Test
    fun `the Retry-After value of a 429 is read by its digits and bounds only`() {
        val cases = listOf(
            "1" to 1,
            "3600" to 3600,
            " 30 " to 30,
            "\t30" to 30,
            "30\t" to 30,
            "0" to null,
            "3601" to null,
            "+30" to null,
            "-5" to null,
            "1.5" to null,
            "3 0" to null,
            "99999999999999999999" to null,
            "\u0663\u0660" to null,
            "" to null,
        )
        for ((text, expected) in cases) {
            val refusal = refusalOf(BytesReply(429, ByteArray(0), listOf("Retry-After" to text)))
            assertEquals("the value was [" + text + "]", expected, refusal.retryAfterSeconds)
        }
    }

    @Test
    fun `a 429 without a Retry-After header or with a repeated one carries none`() {
        assertNull(refusalOf(BytesReply(429, ByteArray(0))).retryAfterSeconds)
        val twice = listOf("Retry-After" to "30", "retry-after" to "60")
        assertNull(refusalOf(BytesReply(429, ByteArray(0), twice)).retryAfterSeconds)
        val lower = listOf("retry-after" to "30")
        assertEquals(30, refusalOf(BytesReply(429, ByteArray(0), lower)).retryAfterSeconds)
    }

    @Test
    fun `a 429 is not permanent and no other status carries a number`() {
        for (status in listOf(500, 503)) {
            val transport = FakeTransport().apply {
                reply = BytesReply(status, ByteArray(0), listOf("Retry-After" to "30"))
            }
            val outcome = uploader(transport).upload(push())
            assertEquals("status " + status, UploadOutcome.FAILED, outcome)
        }
    }
}
