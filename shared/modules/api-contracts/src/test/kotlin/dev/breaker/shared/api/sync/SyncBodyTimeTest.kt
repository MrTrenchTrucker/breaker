package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SyncBodyTimeTest {

    private fun assertRefused(name: String, family: String, block: () -> Unit) {
        try {
            block()
            fail("no refusal of " + name)
        } catch (e: SyncBodyException) {
            assertEquals("vector " + name, "sync body refused: " + family, e.message)
        }
    }

    @Test
    fun `formats epoch zero as the first millisecond of 1970`() {
        assertEquals("epoch zero", "1970-01-01T00:00:00.000Z", SyncBodyCodec.formatCreatedAt(0L))
    }

    @Test
    fun `formats the last millisecond of 9999 with fixed width`() {
        assertEquals("last millisecond", "9999-12-31T23:59:59.999Z", SyncBodyCodec.formatCreatedAt(253402300799999L))
    }

    @Test
    fun `refuses one millisecond after the last representable instant`() {
        assertRefused("one ms after 9999", "created_at") { SyncBodyCodec.formatCreatedAt(253402300800000L) }
    }

    @Test
    fun `refuses one millisecond before the epoch`() {
        assertRefused("minus one ms", "created_at") { SyncBodyCodec.formatCreatedAt(-1L) }
    }

    @Test
    fun `parses a leap day in a leap year`() {
        assertEquals("2028-02-29", 1835395200000L, SyncBodyCodec.parseCreatedAt("2028-02-29T00:00:00.000Z"))
    }

    @Test
    fun `refuses February 30`() {
        assertRefused("2026-02-30", "created_at") { SyncBodyCodec.parseCreatedAt("2026-02-30T12:00:00.000Z") }
    }

    @Test
    fun `refuses second 60`() {
        assertRefused("second 60", "created_at") { SyncBodyCodec.parseCreatedAt("2026-10-21T12:00:60.000Z") }
    }

    @Test
    fun `refuses hour 24`() {
        assertRefused("hour 24", "created_at") { SyncBodyCodec.parseCreatedAt("2026-10-21T24:00:00.000Z") }
    }

    @Test
    fun `refuses created at without milliseconds`() {
        assertRefused("no fraction", "created_at") { SyncBodyCodec.parseCreatedAt("2026-10-21T12:00:00Z") }
    }

    @Test
    fun `refuses one or two millisecond digits`() {
        for (text in listOf("2026-10-21T12:00:00.5Z", "2026-10-21T12:00:00.50Z")) {
            assertRefused(text, "created_at") { SyncBodyCodec.parseCreatedAt(text) }
        }
    }

    @Test
    fun `refuses a year before 1970`() {
        assertRefused("1969-12-31", "created_at") { SyncBodyCodec.parseCreatedAt("1969-12-31T23:59:59.999Z") }
    }

    @Test
    fun `refuses lower case separators and a missing Z`() {
        for (text in listOf("2026-10-21t12:00:00.000Z", "2026-10-21T12:00:00.000z", "2026-10-21T12:00:00.000")) {
            assertRefused(text, "created_at") { SyncBodyCodec.parseCreatedAt(text) }
        }
    }
}
