package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A report built without a Retry-After number has none. */
class SyncReportRetryAfterTest {
    @Test
    fun `a report built without the number has none`() {
        assertNull("android_core: default is null", SyncReport(1, 0).retryAfterSeconds)
        assertNull("android_core: default is null with parked", SyncReport(1, 0, 2).retryAfterSeconds)
    }

    @Test
    fun `a report keeps the number it is given`() {
        assertEquals("android_core: the given number", 45, SyncReport(0, 1, 0, 45).retryAfterSeconds)
    }
}
