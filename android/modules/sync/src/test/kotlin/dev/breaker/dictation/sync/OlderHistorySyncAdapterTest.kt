package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SealedRecord
import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderResult
import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for OlderHistorySyncAdapter: available follows offered, older reads through the page reader, delete runs the delete function once with the id. No network. */
class OlderHistorySyncAdapterTest {

    private val bound = OlderBound(1_700_000_000_000L, null)
    private val clientId = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"

    /** Answers every request with the one reply it was given. */
    private class FixedTransport(private val reply: PullTransportResult) : OlderPullTransport {
        override fun fetch(request: OlderPullRequest): PullTransportResult = reply
    }

    private fun adapter(
        reply: PullTransportResult = PullTransportResult.Answered(200, "{\"items\":[],\"next\":\"1700000000000\",\"more\":false}"),
        keys: RecordKeys? = null,
        deleteRow: (String) -> Boolean = { true },
        offered: () -> Boolean = { true },
    ) = OlderHistorySyncAdapter(FixedTransport(reply), keys, deleteRow, offered)

    private class OpeningKeys(private val opened: ByteArray) : RecordKeys {
        override fun currentKeyVersion(): Int = 1
        override fun seal(clientId: String, body: ByteArray): SealedRecord? = null
        override fun open(clientId: String, keyVersion: Int, encrypted: String): ByteArray? = opened
        override fun reseal(clientId: String, keyVersion: Int, encrypted: String): SealedRecord? = null
        override fun convertSealed(clientId: String, sealedBox: ByteArray): SealedRecord? = null
    }

    @Test
    fun `available answers offered`() {
        assertTrue(adapter(offered = { true }).available())
        assertFalse(adapter(offered = { false }).available())
    }

    @Test
    fun `available reads offered on every call`() {
        var value = true
        val subject = adapter(offered = { value })
        assertTrue(subject.available())
        value = false
        assertFalse(subject.available())
    }

    @Test
    fun `older returns what the page reader returns for a last empty page`() {
        assertSame(OlderResult.NoneOlder, adapter().older(bound))
    }

    @Test
    fun `older maps a 401 answer to SignedOut`() {
        assertSame(OlderResult.SignedOut, adapter(PullTransportResult.Answered(401, "")).older(bound))
    }

    @Test
    fun `older maps an unreachable server to Unavailable`() {
        assertSame(OlderResult.Unavailable, adapter(PullTransportResult.Unreachable).older(bound))
    }

    @Test
    fun `older hands its record keys to the page reader`() {
        val sealedPage = "{\"items\":[{\"client_id\":\"$clientId\",\"change\":1,\"deleted\":false,\"key_version\":1,\"encrypted\":\"sealed\",\"stored_at\":\"t\"}],\"next\":\"1700000000000-1\",\"more\":false}"
        val reply = PullTransportResult.Answered(200, sealedPage)
        val opened = SyncBodyCodec.encodeBody(SyncBody("opened words", SyncSource.LOCAL, "small.en", 1_200L, 1_700_000_000_000L))

        val withKeys = adapter(reply, keys = OpeningKeys(opened)).older(bound) as OlderResult.Page
        assertEquals(listOf("opened words"), withKeys.rows.map { it.text })
        assertEquals(0, withKeys.skipped)

        val withoutKeys = adapter(reply, keys = null).older(bound) as OlderResult.Page
        assertTrue(withoutKeys.rows.isEmpty())
        assertEquals(1, withoutKeys.skipped)
    }

    @Test
    fun `delete calls the delete function exactly once with the id and answers what it answers`() {
        val calls = mutableListOf<String>()
        val subject = adapter(deleteRow = { id ->
            calls.add(id)
            true
        })
        assertTrue(subject.delete("row-1"))
        assertEquals(listOf("row-1"), calls)
    }

    @Test
    fun `a delete function that answers false makes delete answer false`() {
        assertFalse(adapter(deleteRow = { false }).delete("row-2"))
    }

    @Test
    fun `a throwing delete function answers false`() {
        assertFalse(adapter(deleteRow = { throw IllegalStateException("the queue is wedged") }).delete("row-3"))
    }
}
