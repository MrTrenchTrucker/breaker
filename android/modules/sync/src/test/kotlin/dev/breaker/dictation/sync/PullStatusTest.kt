package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for PullStatus: the reading of a pull answer's status. */
class PullStatusTest {

    /** A failure here means PullStatus does not have exactly the four entries in order. */
    @Test
    fun `PullStatus has exactly the four statuses in order`() {
        assertEquals(
            "android_sync: PullStatus must be OK, BAD_CURSOR, GONE, OTHER in this order",
            listOf(PullStatus.OK, PullStatus.BAD_CURSOR, PullStatus.GONE, PullStatus.OTHER),
            PullStatus.entries.toList(),
        )
        assertEquals(
            "android_sync: the names must be exactly these four",
            listOf("OK", "BAD_CURSOR", "GONE", "OTHER"),
            PullStatus.entries.map { it.name },
        )
    }

    /** A failure here means 410 is not read as GONE. */
    @Test
    fun `410 is GONE`() {
        assertEquals("android_sync: 410 must be GONE", PullStatus.GONE, PullStatus.of(410))
    }

    /** Accept control: 409 and 411 are OTHER, not GONE. */
    @Test
    fun `409 and 411 are OTHER`() {
        assertEquals("android_sync: 409 must be OTHER", PullStatus.OTHER, PullStatus.of(409))
        assertEquals("android_sync: 411 must be OTHER", PullStatus.OTHER, PullStatus.of(411))
    }

    /** A failure here means 200 is not read as OK. */
    @Test
    fun `200 is OK`() {
        assertEquals("android_sync: 200 must be OK", PullStatus.OK, PullStatus.of(200))
    }

    /** A failure here means 400 is not read as BAD_CURSOR. */
    @Test
    fun `400 is BAD_CURSOR`() {
        assertEquals("android_sync: 400 must be BAD_CURSOR", PullStatus.BAD_CURSOR, PullStatus.of(400))
    }

    /** A failure here means another status is not read as OTHER. */
    @Test
    fun `401 403 500 0 and minus 1 are OTHER`() {
        assertEquals("android_sync: 401 must be OTHER", PullStatus.OTHER, PullStatus.of(401))
        assertEquals("android_sync: 403 must be OTHER", PullStatus.OTHER, PullStatus.of(403))
        assertEquals("android_sync: 500 must be OTHER", PullStatus.OTHER, PullStatus.of(500))
        assertEquals("android_sync: zero must be OTHER", PullStatus.OTHER, PullStatus.of(0))
        assertEquals("android_sync: minus one must be OTHER", PullStatus.OTHER, PullStatus.of(-1))
    }
}
