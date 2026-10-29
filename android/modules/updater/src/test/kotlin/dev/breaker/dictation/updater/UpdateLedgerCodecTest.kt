package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the text of the ledger file: the round trip, the optional keys, and which texts count as damaged. */
internal class UpdateLedgerCodecTest {

    private val threeTimes = LedgerValues(1_700_000_000_000L, 1_700_000_100_000L, 1_699_999_900_000L, null, null, null)

    private fun assertDamaged(message: String, text: String) {
        assertEquals(message, LedgerValues.EMPTY, LedgerCodec.decode(text))
    }

    @Test
    fun `a state with every key set reads back unchanged`() {
        val record = LedgerRecord(42, 1_700_000_200_000L, "Success")
        val values = LedgerValues(1L, 2L, 3L, record, 7, "The update file could not be downloaded.")
        assertEquals("the round trip must keep every value", values, LedgerCodec.decode(LedgerCodec.encode(values)))
    }

    @Test
    fun `a state with some times unset and no record reads back unchanged`() {
        val values = LedgerValues(null, 2L, null, null, null, null)
        assertEquals("an empty time is null, not damage", values, LedgerCodec.decode(LedgerCodec.encode(values)))
    }

    @Test
    fun `a three-key file reads its three times and no record`() {
        val text = "definitive_check_ms=1700000000000\nattempt_ms=1700000100000\nupdate_ms=1699999900000\n"
        assertEquals("the three times and no record", threeTimes, LedgerCodec.decode(text))
    }

    @Test
    fun `a session id without a written-at time is damaged and reads empty`() {
        assertDamaged("a session id needs its written-at time", "definitive_check_ms=1\nrecord_session_id=42\n")
    }

    @Test
    fun `a duplicate key is damaged and reads empty`() {
        assertDamaged("a repeated key damages the file", "attempt_ms=1\nattempt_ms=2\n")
    }

    @Test
    fun `an unknown key is damaged and reads empty`() {
        assertDamaged("an unknown key damages the file", "attempt_ms=1\nsomething_else=2\n")
    }

    @Test
    fun `a leading zero or a sign is damaged and reads empty`() {
        assertDamaged("a leading zero damages the file", "attempt_ms=01\n")
        assertDamaged("a negative value damages the file", "update_ms=-1\n")
        assertDamaged("a signed value damages the file", "last_seen_code=+4\n")
    }

    @Test
    fun `a value above the range of its key is damaged and reads empty`() {
        assertDamaged("a time above the Long range damages the file", "update_ms=9223372036854775808\n")
        assertDamaged(
            "a session id above the Int range damages the file",
            "record_session_id=2147483648\nrecord_written_at_ms=1\n",
        )
    }

    @Test
    fun `an extra blank line is damaged and reads empty`() {
        assertDamaged("an empty line damages the file", "attempt_ms=1\n\n")
    }

    @Test
    fun `a non-ASCII character is damaged and reads empty`() {
        assertDamaged("a non-ASCII character damages the file", "last_outcome=caf\u00E9\n")
    }

    @Test
    fun `a final status over 32 letters or a last outcome over 200 characters is damaged and reads empty`() {
        val longStatus = "A".repeat(33)
        val longOutcome = "x".repeat(201)
        assertDamaged(
            "a final status over 32 letters damages the file",
            "record_session_id=1\nrecord_written_at_ms=1\nrecord_final_status=$longStatus\n",
        )
        assertDamaged("an outcome over 200 characters damages the file", "last_outcome=$longOutcome\n")
    }

    @Test
    fun `a last outcome with a control character is damaged and reads empty`() {
        assertDamaged("a control character damages the file", "last_outcome=a\tb\n")
    }

    @Test
    fun `a text without its final line break is damaged and reads empty`() {
        assertDamaged("a text without a final line break is damaged", "definitive_check_ms=1\nattempt_ms=5")
    }

    @Test
    fun `encoding a value the file cannot hold is refused`() {
        val nonAscii = LedgerValues(null, null, null, null, null, "caf\u00E9")
        val negative = LedgerValues(-1L, null, null, null, null, null)
        val refusedOutcome = runCatching { LedgerCodec.encode(nonAscii) }.exceptionOrNull()
        val refusedTime = runCatching { LedgerCodec.encode(negative) }.exceptionOrNull()
        assertTrue("a non-ASCII outcome must be refused, got $refusedOutcome", refusedOutcome is IllegalArgumentException)
        assertTrue("a negative time must be refused, got $refusedTime", refusedTime is IllegalArgumentException)
    }
}
