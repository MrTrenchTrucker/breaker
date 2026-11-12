package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SealedRecord
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderResult
import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for OlderPageReader: the status answers, the page it decodes, the rows it leaves out and counts,
 * and the request it sends. Every transport here is a fake; nothing touches a network. */
class OlderPageReaderTest {

    private val c1 = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
    private val c2 = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e70"
    private val bound = OlderBound(1_700_000_000_000L, null)

    private fun liveRecord(clientId: String, encrypted: String, keyVersion: Int = 0): String =
        """{"client_id":"$clientId","change":1,"deleted":false,"key_version":$keyVersion,"encrypted":"$encrypted","stored_at":"t"}"""

    private fun deleteRecord(clientId: String): String =
        """{"client_id":"$clientId","change":2,"deleted":true,"stored_at":"t"}"""

    private fun pageOf(vararg items: String, next: String = "1700000100000-42", more: Boolean = true): String =
        """{"items":[${items.joinToString(",")}],"next":"$next","more":$more}"""

    private fun bodyOf(
        text: String = "words",
        source: SyncSource = SyncSource.LOCAL,
        model: String = "small.en",
        durationMs: Long = 1_200,
        createdAtMs: Long = 1_700_000_000_000L,
    ): SyncBody = SyncBody(text, source, model, durationMs, createdAtMs)

    private fun encryptedOf(body: SyncBody): String = SyncBodyCodec.encodePlain(body)

    /** A transport that answers [status] with [body] and remembers the requests. */
    private class AnsweringTransport(val status: Int, val body: String) : OlderPullTransport {
        val requests = mutableListOf<OlderPullRequest>()
        override fun fetch(request: OlderPullRequest): PullTransportResult {
            requests.add(request)
            return PullTransportResult.Answered(status, body)
        }
    }

    /** Keys whose `open` answers [opened] for every record. */
    private class OpeningKeys(private val opened: ByteArray?) : RecordKeys {
        override fun currentKeyVersion(): Int = 1
        override fun seal(clientId: String, body: ByteArray): SealedRecord? = null
        override fun open(clientId: String, keyVersion: Int, encrypted: String): ByteArray? = opened
        override fun reseal(clientId: String, keyVersion: Int, encrypted: String): SealedRecord? = null
        override fun convertSealed(clientId: String, sealedBox: ByteArray): SealedRecord? = null
    }

    private fun read(body: String, keys: RecordKeys? = null): OlderResult =
        OlderPageReader.read(AnsweringTransport(200, body), bound, keys)

    @Test
    fun `a page of live rows decodes with every body field and carries its next bound and more`() {
        val body = bodyOf(text = "spoken words", source = SyncSource.SERVER, model = "base.en", durationMs = 2_300, createdAtMs = 1_700_000_123_000L)
        val result = read(pageOf(liveRecord(c1, encryptedOf(body))))
        val expected = Transcription(
            id = c1,
            text = "spoken words",
            source = TranscriptionSource.SERVER,
            model = "base.en",
            durationMs = 2_300,
            createdAt = 1_700_000_123_000L,
        )
        assertEquals(OlderResult.Page(listOf(expected), 0, OlderBound(1_700_000_100_000L, 42L), true), result)
    }

    @Test
    fun `the last page says more is false`() {
        val result = read(pageOf(liveRecord(c1, encryptedOf(bodyOf())), more = false))
        assertEquals(false, (result as OlderResult.Page).more)
    }

    @Test
    fun `rows keep the order the server sent`() {
        val result = read(pageOf(liveRecord(c1, encryptedOf(bodyOf(text = "first"))), liveRecord(c2, encryptedOf(bodyOf(text = "second")))))
        assertEquals(listOf("first", "second"), (result as OlderResult.Page).rows.map { it.text })
    }

    @Test
    fun `a delete record is left out and counted`() {
        val result = read(pageOf(deleteRecord(c1), liveRecord(c2, encryptedOf(bodyOf()))))
        val page = result as OlderResult.Page
        assertEquals(listOf(c2), page.rows.map { it.id })
        assertEquals(1, page.skipped)
    }

    @Test
    fun `a row whose body cannot be read is left out and counted`() {
        val result = read(pageOf(liveRecord(c1, "not-base64!!!"), liveRecord(c2, encryptedOf(bodyOf()))))
        val page = result as OlderResult.Page
        assertEquals(listOf(c2), page.rows.map { it.id })
        assertEquals(1, page.skipped)
    }

    @Test
    fun `a row that fails the transcription guards is left out and counted`() {
        val result = read(pageOf(liveRecord(c1, encryptedOf(bodyOf(model = ""))), liveRecord(c2, encryptedOf(bodyOf()))))
        val page = result as OlderResult.Page
        assertEquals(listOf(c2), page.rows.map { it.id })
        assertEquals(1, page.skipped)
    }

    @Test
    fun `a sealed row without keys is left out and counted`() {
        val result = read(pageOf(liveRecord(c1, encryptedOf(bodyOf()), keyVersion = 1)), keys = null)
        assertEquals(OlderResult.Page(emptyList(), 1, OlderBound(1_700_000_100_000L, 42L), true), result)
    }

    @Test
    fun `a sealed row is read when the keys open it`() {
        val keys = OpeningKeys(SyncBodyCodec.encodeBody(bodyOf(text = "opened words")))
        val result = read(pageOf(liveRecord(c1, "sealed", keyVersion = 1)), keys)
        val page = result as OlderResult.Page
        assertEquals(listOf("opened words"), page.rows.map { it.text })
        assertEquals(0, page.skipped)
    }

    @Test
    fun `a sealed row the keys cannot open is left out and counted`() {
        val result = read(pageOf(liveRecord(c1, "sealed", keyVersion = 1)), OpeningKeys(null))
        assertEquals(OlderResult.Page(emptyList(), 1, OlderBound(1_700_000_100_000L, 42L), true), result)
    }

    @Test
    fun `an empty last page means there are no older rows`() {
        assertEquals(OlderResult.NoneOlder, read(pageOf(next = "1700000000000", more = false)))
    }

    @Test
    fun `an empty page that says more is still a page`() {
        val result = read(pageOf(next = "1700000000000-7", more = true))
        assertEquals(OlderResult.Page(emptyList(), 0, OlderBound(1_700_000_000_000L, 7L), true), result)
    }

    @Test
    fun `each status the route can answer maps to its own result, even with a readable page`() {
        val readable = pageOf(liveRecord(c1, encryptedOf(bodyOf())))
        val expected = mapOf(
            400 to OlderResult.BadRequest,
            401 to OlderResult.SignedOut,
            403 to OlderResult.NotAllowed,
            429 to OlderResult.Busy(null),
            500 to OlderResult.Unavailable,
            503 to OlderResult.Unavailable,
            404 to OlderResult.Unavailable,
        )
        for ((status, result) in expected) {
            assertEquals("status $status", result, OlderPageReader.read(AnsweringTransport(status, readable), bound))
        }
    }

    @Test
    fun `an unreachable server is unavailable`() {
        val transport = OlderPullTransport { PullTransportResult.Unreachable }
        assertEquals(OlderResult.Unavailable, OlderPageReader.read(transport, bound))
    }

    @Test
    fun `a page that cannot be decoded is unavailable`() {
        assertEquals(OlderResult.Unavailable, read("not json at all"))
    }

    @Test
    fun `a next that is not a bound is unavailable`() {
        assertEquals(OlderResult.Unavailable, read(pageOf(liveRecord(c1, encryptedOf(bodyOf())), next = "any-string")))
    }

    @Test
    fun `the request carries the path and the older than bound`() {
        val transport = AnsweringTransport(200, pageOf())
        OlderPageReader.read(transport, OlderBound(1_700_000_000_000L, 5L))
        OlderPageReader.read(transport, OlderBound(1_700_000_000_000L, null))
        assertEquals(2, transport.requests.size)
        assertEquals("/v1/transcriptions/older", transport.requests[0].path())
        assertEquals("older_than=1700000000000-5", transport.requests[0].query())
        assertEquals("older_than=1700000000000", transport.requests[1].query())
    }

    @Test
    fun `a last page of only delete records is a page that counts them and not none older`() {
        val result = read(pageOf(deleteRecord(c1), deleteRecord(c2), more = false))
        assertEquals(OlderResult.Page(emptyList(), 2, OlderBound(1_700_000_100_000L, 42L), false), result)
    }

    @Test
    fun `a last page of only unreadable rows is a page that counts them and not none older`() {
        val result = read(pageOf(liveRecord(c1, "not-base64!!!"), more = false))
        assertEquals(OlderResult.Page(emptyList(), 1, OlderBound(1_700_000_100_000L, 42L), false), result)
    }

    @Test
    fun `a row written by the phone is read back with the local source`() {
        val body = bodyOf(text = "phone words", source = SyncSource.LOCAL, model = "small.en", durationMs = 1_200, createdAtMs = 1_700_000_000_000L)
        val result = read(pageOf(liveRecord(c1, encryptedOf(body))))
        val expected = Transcription(
            id = c1,
            text = "phone words",
            source = TranscriptionSource.LOCAL,
            model = "small.en",
            durationMs = 1_200,
            createdAt = 1_700_000_000_000L,
        )
        assertEquals(OlderResult.Page(listOf(expected), 0, OlderBound(1_700_000_100_000L, 42L), true), result)
    }
}
