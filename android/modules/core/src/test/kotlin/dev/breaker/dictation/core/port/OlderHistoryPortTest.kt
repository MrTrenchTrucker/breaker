package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the OlderHistory port shape: an adapter can be written against it, the bound renders and
 * parses back, and the result kinds carry only domain values. */
class OlderHistoryPortTest {

    @Test
    fun `an older history adapter can be written against the port`() {
        val older = object : OlderHistory {
            override fun available(): Boolean = true
            override fun older(bound: OlderBound): OlderResult = OlderResult.Page(
                rows = emptyList(),
                skipped = 0,
                next = bound,
                more = false,
            )
            override fun delete(id: String): Boolean = false
        }
        assertTrue(older.available())
        val bound = OlderBound(1_700_000_000_000L, null)
        assertEquals(OlderResult.Page(emptyList(), 0, bound, false), older.older(bound))
        assertFalse(older.delete("t-1"))
    }

    @Test
    fun `older bound render parses back exactly`() {
        val roundTrips = listOf(
            OlderBound(1_700_000_000_000L, null),
            OlderBound(1_700_000_000_000L, 3L),
            OlderBound(0L, null),
            OlderBound(0L, 0L),
        )
        for (bound in roundTrips) {
            assertEquals("render parses back: ${bound.render()}", bound, OlderBound.parse(bound.render()))
        }
    }

    @Test
    fun `older bound refuses malformed text`() {
        val refused = listOf(
            null, "", "abc", "-1", "5-", "-5", "5-3-1", " 5 ", "5 3", "5-3x", "+5", "5-+3",
            "1234567890123456", "5-9223372036854775808", "5-12345678901234567890", "5-00000000000000000001",
        )
        for (bad in refused) {
            assertNull("refuses $bad", OlderBound.parse(bad))
        }
    }

    @Test
    fun `older bound accepts leading zeros and the largest values the server sends`() {
        val zeros = OlderBound.parse("007-03")
        assertNotNull(zeros)
        assertEquals(OlderBound(7L, 3L), zeros)
        val largest = OlderBound.parse("999999999999999-9223372036854775807")
        assertEquals(OlderBound(999_999_999_999_999L, Long.MAX_VALUE), largest)
    }

    @Test
    fun `older bound renders the change part only when there is one`() {
        assertEquals("1700000100000", OlderBound(1_700_000_100_000L, null).render())
        assertEquals("1700000100000-42", OlderBound(1_700_000_100_000L, 42L).render())
    }

    @Test
    fun `a page carries its rows, its skipped count, its next bound and more`() {
        val row = Transcription(id = "t-1", text = "spoken words", source = TranscriptionSource.SERVER, model = "base.en", durationMs = 2_300, createdAt = 1_700_000_123_000L)
        val page = OlderResult.Page(
            rows = listOf(row),
            skipped = 2,
            next = OlderBound(1_700_000_100_000L, 42L),
            more = true,
        )
        assertEquals(listOf(row), page.rows)
        assertEquals(2, page.skipped)
        assertEquals("1700000100000-42", page.next.render())
        assertTrue(page.more)
    }

    @Test
    fun `the result kinds are distinct and the busy kind keeps its wait`() {
        val kinds: List<OlderResult> = listOf(
            OlderResult.NoneOlder,
            OlderResult.SignedOut,
            OlderResult.NotAllowed,
            OlderResult.Busy(retryAfterSeconds = 30),
            OlderResult.Unavailable,
            OlderResult.BadRequest,
        )
        assertEquals(kinds.size, kinds.toSet().size)
        assertEquals(30, OlderResult.Busy(retryAfterSeconds = 30).retryAfterSeconds)
        assertNull(OlderResult.Busy(retryAfterSeconds = null).retryAfterSeconds)
    }
}
