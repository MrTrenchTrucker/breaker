package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Test

/** formatInstantMs always prints three fractional digits; formatStoredAt keeps printing whole seconds. */
internal class SyncTimeFormatInstantTest {

    @Test
    fun `a whole second prints three zero digits`() {
        assertEquals("sync-api: whole second", "2023-11-14T22:13:20.000Z", formatInstantMs(1_700_000_000_000L))
    }

    @Test
    fun `milliseconds print three digits`() {
        assertEquals("sync-api: 123 ms", "2023-11-14T22:13:20.123Z", formatInstantMs(1_700_000_000_123L))
        assertEquals("sync-api: 999 ms", "2023-11-14T22:13:20.999Z", formatInstantMs(1_700_000_000_999L))
    }

    @Test
    fun `a small millisecond remainder is padded with zeros`() {
        assertEquals("sync-api: 7 ms", "2023-11-14T22:13:20.007Z", formatInstantMs(1_700_000_000_007L))
        assertEquals("sync-api: 70 ms", "2023-11-14T22:13:20.070Z", formatInstantMs(1_700_000_000_070L))
    }

    @Test
    fun `zero prints the epoch with three zero digits`() {
        assertEquals("sync-api: epoch", "1970-01-01T00:00:00.000Z", formatInstantMs(0L))
    }

    @Test
    fun `the last millisecond of a second and the next whole second differ`() {
        assertEquals("sync-api: 999 ms", "1970-01-01T00:00:00.999Z", formatInstantMs(999L))
        assertEquals("sync-api: one second", "1970-01-01T00:00:01.000Z", formatInstantMs(1_000L))
    }

    @Test
    fun `a negative value is refused`() {
        TestDatabases.expectFailure<IllegalArgumentException>("formatInstantMs of minus one") { formatInstantMs(-1L) }
    }

    @Test
    fun `formatStoredAt still prints whole seconds with no fraction`() {
        assertEquals("sync-api: whole second", "2023-11-14T22:13:20Z", formatStoredAt(1_700_000_000_000L))
        assertEquals("sync-api: with milliseconds", "2023-11-14T22:13:20Z", formatStoredAt(1_700_000_000_123L))
        assertEquals("sync-api: epoch", "1970-01-01T00:00:00Z", formatStoredAt(0L))
    }
}
