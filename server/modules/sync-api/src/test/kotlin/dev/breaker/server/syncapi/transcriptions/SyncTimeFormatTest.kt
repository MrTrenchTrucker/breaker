package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.tokens.TokenFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

internal class SyncTimeFormatTest {

    @Test
    fun `the epoch is written as midnight on the first of January 1970`() {
        assertEquals("sync-api: the epoch", "1970-01-01T00:00:00Z", formatStoredAt(0L))
    }

    @Test
    fun `the fixed test clock is written as its calendar date and time`() {
        assertEquals(
            "sync-api: the fixed test clock",
            "2023-11-14T22:13:20Z",
            formatStoredAt(TokenFixtures.START.toEpochMilli()),
        )
    }

    @Test
    fun `the leap day of 2000 is written as the twenty ninth of February`() {
        assertEquals(
            "sync-api: the leap day",
            "2000-02-29T00:00:00Z",
            formatStoredAt(1000L * 86400L * 11016L),
        )
    }

    @Test
    fun `milliseconds are dropped and the last second of that day is written`() {
        assertEquals(
            "sync-api: the last second of the leap day",
            "2000-02-29T23:59:59Z",
            formatStoredAt(1000L * 86400L * 11016L + 1000L * 86399L + 999L),
        )
    }
}
