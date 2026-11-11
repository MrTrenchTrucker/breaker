package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Retry-After seconds of a 429 reach the report unchanged at both ends of the allowed range. */
class QueuedSyncServiceRetryAfterBoundsTest {

    private val rig = ServiceRig()

    @Test
    fun `a 429 with 3600 seconds reaches the report as 3600`() {
        rig.queue(clientId(1))
        rig.uploader.thenThrow(PermanentRefusalException(429, "rate limited", retryAfterSeconds = 3600))

        assertEquals("android_sync: the upper bound passes through", SyncReport(0, 1, 0, 3600), rig.push())
    }

    @Test
    fun `a 429 with 1 second reaches the report as 1`() {
        rig.queue(clientId(1))
        rig.uploader.thenThrow(PermanentRefusalException(429, "rate limited", retryAfterSeconds = 1))

        assertEquals("android_sync: the lower bound passes through", SyncReport(0, 1, 0, 1), rig.push())
    }
}
